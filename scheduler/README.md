# Nine Builders Independent Scheduler 0.3

A dependency-free, single-process scheduling service with a local browser dashboard.
It is intended to become shared infrastructure for the Analysis Engine and the
Samsung Conversational Builder.

## Requirements

- Node.js 20 or newer
- No third-party packages
- A local computer that can keep a Node.js process running

## Start the service

From this directory:

```sh
npm start
```

By default, the service:
- Checks for due work every 15 seconds.
- Stores task definitions and run history in `./data/scheduler.json`.
- Opens its dashboard at `http://127.0.0.1:4174`.
- Binds only to loopback. It does not listen on the local network or internet.
- Takes a single-instance lock beside the state file.

Configuration:
- `SCHEDULER_STATE_FILE`: persistent state-file path.
- `SCHEDULER_POLL_MS`: 1,000–300,000 milliseconds.
- `SCHEDULER_LEASE_HEARTBEAT_MS`: process-lock heartbeat interval, 1,000–300,000 milliseconds; defaults to 15,000.
- `SCHEDULER_DASHBOARD_PORT`: dashboard port, default 4174.
- `SCHEDULER_DASHBOARD_HOST`: must remain `127.0.0.1`, `::1`, or `localhost`.
- `SCHEDULER_DASHBOARD_TOKEN`: optional bearer token protecting management APIs.
- `GITHUB_TOKEN` or `GH_TOKEN`: optional GitHub token with Actions read access.
- `BUILDER_EXECUTOR_MODULE`: optional absolute or working-directory-relative path to an operator-installed Node module exporting `createBuilderExecutor()`. The module must return an async role executor. It is not loaded from task data.
- `BUILDER_RUN_STATE_FILE`: optional path for the separate Nine Builders run ledger; defaults to `<SCHEDULER_STATE_FILE>.builder-runs.json`.

If a dashboard token is configured, open the dashboard first and enter the token
in its access field. The page and static assets are served without the token so
the user can enter it; task and history APIs require it. The token is held in the
page's memory and is not saved to browser storage.

## Dedicated Nine Builders runner

Use `npm run start:nine-builders` for the overnight Builder workload rather than
adding it to the general scheduler. This entry point refuses to start unless
its state file, lock file, and loopback dashboard port are explicitly separated
from the general scheduler, and an operator-owned Builder executor module is
configured. It fails before importing the shared runner or loading host code
when these requirements are missing or conflicting.

Example (choose private paths and an unused local port on the host):

```sh
NINE_BUILDERS_SCHEDULER_STATE_FILE="$HOME/.yggdrasil/nine-builders/scheduler.json" \
NINE_BUILDERS_DASHBOARD_PORT=4175 \
BUILDER_EXECUTOR_MODULE="$HOME/.yggdrasil/host/builder-executor.mjs" \
npm run start:nine-builders
```

If the general scheduler uses a non-default state file or dashboard port, also
set `NINE_BUILDERS_GENERAL_STATE_FILE` and
`NINE_BUILDERS_GENERAL_DASHBOARD_PORT` to those exact values. The dedicated
runner still uses the normal loopback-only dashboard, process lock, recovery,
and shutdown behavior. This provides same-host process/state separation; it
does not supervise either process, guarantee overnight uptime, or provide
distributed execution. The operator remains responsible for keeping both
processes alive and monitoring their health.

## Dashboard

The dashboard provides:
- Current scheduled tasks and their next execution times.
- Create a daily or weekly GitHub Actions monitor.
- Pause and resume tasks.
- Delete tasks while retaining their execution history.
- View recent execution records and errors.
- Optional browser notifications for new failed scheduler executions.

Browser notifications require explicit permission and work only while the
dashboard page remains open. The scheduler itself continues running when the
dashboard is closed, as long as the host process remains alive.

A new GitHub monitor records a baseline on its first successful check. Existing
workflow runs are not reported as new failures. Later newly completed failures
are stored as failed scheduler executions and appear in the dashboard. The
monitor reads only the exact repository, branch and workflow names configured
in the task. It does not edit code, rerun workflows, merge pull requests, or
change repository settings. It scans up to 1,000 recent runs per check and fails
visibly without advancing its cursor if more history is present.

## Command-line management

The dashboard is the preferred interface while the service is running. The
offline CLI acquires the same process lock, so it refuses to run while the
service is active; stop the service before using it.

```sh
npm run scheduler -- list
npm run scheduler -- add-daily-heartbeat morning-check "Morning check" America/New_York 9 0
npm run scheduler -- add-weekly-heartbeat monday-check "Monday check" America/New_York 9 0 1
npm run scheduler -- add-github-watch build-watch "Watch build failures" mcoke9-lgtm/Yggdrasil-Lean analysis-engine-prototype-001 America/New_York 9 0 "Analysis Engine" "Lean build and analysis engine checks"
npm run scheduler -- add-nine-builders-run overnight-research "Nine Builders overnight research" America/New_York 22 0 vault:task-001
npm run scheduler -- resume-nine-builders resume-overnight "Resume interrupted Nine Builders run" builder:run-id
npm run scheduler -- continue-nine-builders continue-overnight "Continue legacy Nine Builders run" builder:legacy-run-id
npm run scheduler -- pause morning-check
npm run scheduler -- resume morning-check
npm run scheduler -- runs morning-check
```

Weekly days use 0=Sunday through 6=Saturday.

## Service lifecycle and recovery

The runner is a foreground Node.js process, not yet an installed operating-system
service. It handles SIGINT and SIGTERM, waits for the active tick, closes the
dashboard, and releases its process lock. The lock records an owner PID, random
ownership token, creation time, and heartbeat time. The runner refreshes the
heartbeat every 15 seconds by default; if it cannot prove continued ownership,
it aborts active handlers and shuts down rather than continuing as a second
writer. The lock directory must be owner-only on POSIX hosts. Stale-lock
reclamation is serialized through a separate recovery file and rechecks the
owner token and PID; malformed locks or a competing recovery fail closed.

The heartbeat is a local process-liveness signal, not a time-expiring
distributed lease or a fencing token. A live PID is never displaced solely
because its heartbeat is old. This avoids stealing a lock from a slow but still
running process, but it means PID reuse or a crash during stale-lock recovery
can require operator inspection. The store and lock remain designed for one
local process and one host, not distributed workers.

If the process stops while a handler is running, version-2 runs are marked
interrupted or expired after restart. They are not automatically replayed
because the external outcome may be unknown. Version-1 ledger records are
left unchanged and remain read-only. Task state uses fsynced atomic file
replacement. Every new Builder run persists its task-vault reference alongside
the run ID, so explicit version-2 resume restores the original task binding
and rejects any caller-supplied mismatch.

The `resume-nine-builders` CLI command creates a one-shot task for a specified
interrupted version-2 run. The `continue-nine-builders` command is for an
interrupted or expired version-1 run: it asks the configured executor to
validate the exact encrypted source history, fork it into a distinct run/thread,
and reconcile every role before creating a separate version-2 ledger run
linked by `supersedesRunId`. Successful roles must match their exact durable
vault turns. Failed, interrupted, or still-running roles require exactly one
durable turn; without it, continuation stops for manual reconciliation rather
than repeating a possibly completed provider call. Pending roles may run fresh
only when no prior turn exists. Explicitly skipped roles remain skipped; Baldr
cannot be skipped. The v1 record and source vault thread remain unchanged.
Baldr receives full successful turn bodies from the encrypted vault plus typed
role outcome references, never plaintext summaries. Both commands create
one-shot tasks; neither command silently replays the old run. One-shot tasks become disabled
after their single attempt and retain a visible terminal state.

## Long-running task isolation

The current scheduler executes due handlers sequentially within a tick and waits
for each handler to return. An eight-hour Nine Builders handler therefore
occupies that scheduler process's active tick; other tasks in the same scheduler
instance will not be dispatched until it finishes. Until the scheduler gains a
separately supervised worker pool, isolate overnight Builder schedules in a
dedicated runner instance with its own `SCHEDULER_STATE_FILE`, lock file, and
dashboard port. Keep GitHub monitors and short maintenance tasks in a separate
scheduler instance. This is process isolation on one host, not distributed
execution or proof that either process will remain alive overnight.

## Safety boundaries

- Only explicitly registered handler functions can execute.
- Task data cannot select arbitrary shell commands or dynamically load code. The optional Builder executor module is loaded only from the operator-controlled `BUILDER_EXECUTOR_MODULE` environment setting at host startup.
- Nine Builders task payloads contain only an opaque `taskReference`; the host executor is responsible for resolving it from its own secure task store. Do not place prompts, provider credentials, or tokens in scheduler task payloads.
- The dashboard binds only to loopback and checks Host and Origin headers.
- API request bodies are size-limited; responses include restrictive browser
  security headers.
- Optional dashboard bearer-token protection is available.
- GitHub credentials are read from environment variables and never saved in
  task data or source control.
- The service does not expose a public or LAN management endpoint.

Before unattended production use, add encrypted/managed secret storage, backup
and restore procedures, bounded run-history retention, external health checks,
and platform-specific service installation. The current service has no remote
push notification channel and is not yet installed on a user's computer or
Android device.

## Nine Builders bounded-run ledger (version 2)

The `src/run-ledger.js` module adds a durable metadata contract for a Nine Builders
work window. It records all nine role identities, an eight-hour maximum deadline,
per-role attempts and outcomes, bounded checkpoint metadata, interruption state,
and explicit safe-to-resume declarations. Baldr is gated until the other eight
roles have completed or been explicitly skipped, and a Baldr review outcome
cannot be silently skipped.

The ledger stores checkpoint metadata (maximum 16 KiB per checkpoint), not model
prompts, credentials, or full conversation payloads. It uses the scheduler store
interface but is persisted in a separate `*.builder-runs.json` file by default,
so ledger writes cannot overwrite concurrent task and scheduler-run updates.

**Storage confidentiality:** the scheduler and Builder-run JSON state files are
not encrypted at rest. On POSIX systems the store enforces an owner-only
directory (0700) and file (0600), but filesystem permissions are not encryption.
New version-2 Builder records contain typed statuses/error codes, timestamps,
opaque vault-turn references, and a strict checkpoint allowlist; they do not
persist free-text summaries, exception messages, or interruption reasons.
Builder completion/interruption stdout events are metadata-only as well.
Checkpoint metadata is not arbitrary JSON. Use opaque non-semantic task
references only. Never place prompts, response bodies, access tokens,
credentials, private keys, or unnecessary personal data in scheduler state.
The encrypted task vault is the designated store for task text, consent, full
per-run conversation history, and detailed diagnostics. Protect and back up
its encryption key separately.

Version-1 Builder records remain readable legacy plaintext and are immutable:
the runtime does not rewrite or automatically migrate them. Same-run
`resume-nine-builders` applies only to version-2 runs. For a version-1 run,
use `continue-nine-builders` to request a separate version-2 continuation.
That path requires the executor and task vault to verify and fork the exact
encrypted source thread, and to reconcile each v1-successful role against one
matching durable vault turn. Missing or mismatched evidence fails closed.
The source-history digest remains encrypted in the vault; it is not copied into
the plaintext ledger.

**Important limitation:** the ledger and adapter are now connected to the
persistent runner through an optional host module, but this is not yet a
production model executor or a verified unattended host. The runner does not
ship provider routes or credentials. A checkpoint is resumable only when the
worker explicitly records `safeToResume: true`. The handler also requires the
executor to declare `supportsVerifiedResumeCheckpoint = true` and passes the
exact checkpoint metadata back to it. Before any provider call, the executor
must verify that the referenced turn still exists in the same run/thread and
that its turn ID, response hash, type, and evidence status match. The reference
OpenAI-compatible executor performs this check; a custom executor without the
capability fails closed. Otherwise recovery stops for reconciliation. When no module is configured, the runner logs
`nine_builders_executor_unconfigured`; a due Nine Builders task then fails
visibly with “No handler registered” rather than pretending work occurred.

### Scheduler-to-Builder handler adapter

`src/builder-run-handler.js` registers the `nine-builders.run` handler when
the host supplies both a `BuilderRunLedger` and an explicit executor function.
It invokes the nine roles in the canonical sequence, records per-role checkpoints
and outcomes, and passes the first eight typed outcomes to Baldr for the final review
pass. Each successful outcome reference must match exactly one turn in the
encrypted run history. Baldr receives the full turn history from the vault,
not summaries copied from the plaintext ledger. A failed role is represented
by a typed error code and does not erase the remaining roles' chance to contribute.

The adapter deliberately requires the host to inject the executor. It does not
invent provider routes, credentials, consent, or model identity. It passes a
cooperative cancellation signal to each role executor, enforces the recorded
eight-hour deadline, and records abandoned version-2 active runs as interrupted on recovery. Version-1
records are left unchanged for manual continuation. Executors must honor the
signal; a provider adapter that ignores cancellation cannot be forcibly stopped
by this in-process contract. The repository does not ship a production provider executor. The host module is an
explicit integration boundary; it must resolve the opaque task reference, enforce
provider-specific consent and disclosure, and implement role-specific prompts,
provenance, bounded output, and cancellation. Automated tests use a deterministic
fake executor; that verifies runner registration, orchestration, and ledger
behavior, not live model behavior or an overnight host.

### Host executor module contract

A host module is deliberately operator-owned because it is where secure task
lookup, route configuration, consent enforcement, and provider credentials meet.
The following is an interface sketch, not a working provider integration:

```js
export async function createBuilderExecutor() {
  const taskVault = await openOperatorManagedTaskVault();
  const routes = await loadOperatorManagedRoutes();

  return async function executeBuilderRole(role, context) {
    const task = await taskVault.get(context.task.taskReference);
    if (!task) throw new Error("Unknown secure task reference");
    const route = routes[role];
    if (!route) throw new Error("No approved route for " + role);
    if (context.signal.aborted) throw context.signal.reason;

    // Implement the role-specific prompt, consent checks, provider call,
    // provenance capture, output limits, and AbortSignal-aware transport here.
    const result = await callApprovedProvider(route, {
      role,
      task,
      priorOutcomes: context.outcomes,
      signal: context.signal
    });

    // Persist only typed references and metadata, never arbitrary checkpoint text.
    await context.checkpoint({
      checkpointId: result.checkpointId,
      vaultTurnId: result.checkpointId,
      responseSha256: result.responseSha256,
      turnType: result.turnType,
      evidenceStatus: result.evidenceStatus,
      safeToResume: result.safeToResume === true
    });
    return {
      status: "succeeded",
      outcomeRef: result.checkpointId,
      turnType: result.turnType,
      evidenceStatus: result.evidenceStatus,
      responseSha256: result.responseSha256
    };
  };
}
```

The executor is responsible for passing the first eight roles' outcomes into
Baldr's final review through `context.outcomes`. It must not treat a distinct
endpoint/model label as proof of independent ownership or model weights. If the
provider transport cannot abort a request, the executor must report that
limitation and must not claim cancellation has stopped the external operation.

## OpenAI-compatible host executor (reference adapter)

The repository now includes `src/openai-compatible-executor.js`, a reference host adapter for providers that implement the OpenAI Chat Completions request/response shape. It is an optional executor module, not a configured provider account or a live model connection.

Set these host-only environment variables when starting a dedicated Builder runner:

- `BUILDER_EXECUTOR_MODULE`: absolute path to this module, for example `./src/openai-compatible-executor.js`.
- `BUILDER_HOST_CONFIG_FILE`: absolute path to an operator-owned JSON route file.
- `BUILDER_TASK_VAULT_MODULE`: absolute path to an operator-owned ES module exporting `createTaskVault({ evidenceGateway })` and returning an object with `getTask(taskReference)` and `appendBuilderResult(taskReference, record)`. For version-1 continuation it must additionally expose `getRunHistoryManifest(taskReference, { runId })` and `forkRunHistory(taskReference, options)`; the encrypted local vault provides these methods. Older custom vault modules remain usable for ordinary v2 runs but fail closed for v1 continuation. When `BUILDER_EVIDENCE_GATEWAY_MODULE` is configured, this factory is required so the encrypted task vault receives the same verified read-only gateway used by the executor. Without a gateway, legacy direct exports remain supported. A trusted user-action host may separately expose `appendUserAdoption(taskReference, request)` to its authenticated user interface, never to Builder model routes.
- `BUILDER_CREDENTIALS_MODULE`: absolute path to an operator-owned ES module exporting `readSecret(secretRef)`.
- `BUILDER_EVIDENCE_GATEWAY_MODULE`: optional absolute path to an owner-only ES module implementing the read-only GitHub evidence gateway contract described above. Its factory is called with the host credential resolver and configured `evidenceGatewaySecretRef`; the resulting gateway is injected into both the executor and `createTaskVault`.
- `BUILDER_USER_AUTHORIZATION_PUBLIC_KEYS_JSON`: optional JSON object mapping operator-managed key IDs to Ed25519 public-key PEM strings. It enables vault-side verification of signed user adoption actions; it does not contain private signing keys.

A secret-free route-file example is in `examples/builder-host-config.example.json`. Copy it outside the repository, replace the placeholder endpoints/model IDs with approved routes, and set the file to owner-only permissions (for example, `chmod 600`). The route file contains provider IDs, endpoints, model IDs, and secret references only; it must not contain API tokens or task prompts. On POSIX hosts, the adapter rejects a route file that is group/other-readable or writable, or is a symlink.

The task-vault module owns task contents, approval records, dedicated run threads, and result history. `getTask(taskReference, { runId })` must create or retrieve one dedicated thread for that scheduled run and return an object containing a non-empty `prompt`, `threadId`, optional `history` array, and a consent record with `status: "USER_APPROVED"`, `scope: "NINE_BUILDERS_OVERNIGHT"`, a valid `approvedAt` timestamp, and a 64-character `fingerprint`. Every returned history item must carry the same `runId` and `threadId`; the adapter rejects history from other runs/threads and rejects a thread ID that changes during one run. Do not import unrelated chat history. Use the exported `createOvernightDisclosureFingerprint(routes, prompt)` helper when the user explicitly approves the task. The fingerprint binds the exact task prompt and all nine provider IDs, endpoints, and model IDs. If the prompt or any route changes after approval, the adapter blocks disclosure and requires fresh user approval. The adapter does not provide an approval UI; the operator-owned task vault must record an actual user confirmation and return only an approved task. Android-app consent records are not automatically transferable to this Node host; approval must be recorded in the Node task vault.

`appendBuilderResult(taskReference, record)` must durably persist the role result in the same run-specific thread, make that Builder turn visible in subsequent `getTask` history, and return `{ durable: true, checkpointId: "..." }`. Baldr is blocked unless every successful peer role's `outcomeRef` matches exactly one recorded turn in the dedicated run history. The record includes a stable `runId:ROLE` idempotency key, `threadId`, requested provider/model, provider-reported model when present, response hash, validated reply, and completion time. Store this data in an operator-controlled protected location; do not put prompts or full responses in scheduler task payloads or the scheduler ledger. The task vault should enforce the idempotency key and return the existing result rather than creating a duplicate if the same key is submitted again.

The credentials module resolves each `secretRef` from an OS keychain or another operator-managed secret store. It returns a short-lived token string; the adapter never writes the token to scheduler state or logs. If a route has no `secretRef`, the endpoint is treated as an unauthenticated local service. Use HTTPS for remote endpoints; HTTP is accepted only for loopback development.

### Encrypted local task vault and explicit approval

The repository now includes `src/encrypted-task-vault.js`. It exports a lazy `getTask()` / `appendBuilderResult()` module interface for the host and a `createEncryptedTaskVault()` factory for task setup. It uses AES-256-GCM with a 32-byte key supplied as base64 in `BUILDER_TASK_VAULT_KEY`; the encrypted vault file is selected with `BUILDER_TASK_VAULT_FILE`. On POSIX systems, the vault directory must be owner-only (0700) and the vault file owner-only (0600). The vault rejects symlinks, broad file permissions, oversized state, wrong keys, and conflicting idempotency-key reuse. Task text, consent, and per-run history are encrypted together. Back up the key separately; loss of the key makes the vault unreadable.

The approval command is `node src/approve-builder-task.js`. Set `BUILDER_TASK_REFERENCE`, `BUILDER_TASK_PROMPT_FILE`, `BUILDER_HOST_CONFIG_FILE`, `BUILDER_TASK_VAULT_FILE`, and `BUILDER_TASK_VAULT_KEY` in the local host environment. The task prompt and route config files must be private regular files (0600, not symlinks). The command displays the complete task prompt and all nine provider IDs, model IDs, and endpoint URLs, then requires the operator to type exactly `APPROVE`. Any other response leaves the task unapproved. A changed prompt or route set invalidates the stored approval fingerprint.

The included `src/environment-credentials.js` resolver reads only explicitly named environment variables. For example, secret reference `nine-builders/odin` maps to `BUILDER_SECRET_NINE_BUILDERS_ODIN`. Configure those values in the host's protected environment or service secret manager; never place tokens in route JSON, task prompts, the repository, or ChatGPT. No credential values are supplied by this project.

The adapter requires exactly nine role routes and unique model IDs. Baldr's endpoint and model ID must each differ from every other role. This is routing separation, not cryptographic proof of independent model weights, ownership, or infrastructure. Baldr is invoked only after the other eight roles have recorded outcomes and receives their outcome summaries for independent review.

The canonical role responsibilities are kept aligned across the Android registry and Node host adapter: Odin coordinates; Höðr operates the service runner and GitHub monitor; Loki handles interruption, recovery, time-zone/daylight-saving behavior, and duplicate suppression; Muninn preserves persistence and history; Huginn verifies evidence and sources; Kvasir enforces operational constraints and identifies unsuitable methods; Mari explores complementary alternatives and integration; Týr enforces authorization; Baldr independently reviews the other eight roles.

**Prompt and conversation-contract status:** the Node adapter now includes explicit `NAVIGATING_DIFFICULT_SPACES_MODE` and `BUILDER_IMPROVEMENT_REVIEW_MODE` instructions, plus a run-scoped history validator aligned with the key invariants in Android `BuilderConversationProtocol` version 4. New encrypted-vault records carry protocol version 4, a stable `turnId` matching the durable checkpoint identity, a source timestamp in both ISO and epoch-millisecond form, typed turn/evidence fields, and run/thread identity. Validation covers unique turn IDs, same-run/thread isolation, strictly increasing source timestamps, recipient ownership, addressed-participant response rules, and the append-only correction lifecycle. Legacy records without timestamps are retained as legacy; no historical time is invented. Android and Node do not yet share one compiled schema or persistence implementation, so this is behavioral compatibility rather than proof of byte-for-byte parity.

Node correction records require typed GitHub commit references for IMPLEMENTED and typed GitHub Actions run references for TESTED/VERIFIED. A read-only verifier in `scheduler/src/builder-evidence-reference.js` checks exact repository/commit/branch identity or exact workflow/run/tested-SHA identity plus every job's successful completion. The concrete `scheduler/src/github-rest-evidence-gateway.js` implementation uses GitHub REST GET requests, checks branch ancestry, and paginates all workflow-job pages (up to its explicit safety limit). Idempotent GETs use at most three attempts with bounded exponential backoff, honor only Retry-After values within the configured delay ceiling, and observe cancellation. Gateway calls are also bounded by the verifier's 15-second default timeout and return UNAVAILABLE or UNVERIFIED on missing, incomplete, mismatched, or failed evidence. The provider model executor does not automatically retry ambiguous POST outcomes, avoiding silent duplicate inference or billing. CI uses mocked transports; no live GitHub API request is made by these tests. The executor and encrypted vault fail closed: a CORRECTION_UPDATE is not persisted unless the injected gateway returns matching VERIFIED evidence with a source timestamp.

Before importing operator-supplied modules, the host validates the route configuration, task-vault module, credentials module, and optional evidence-gateway module. Each must be a regular non-symlink file, owner-only (mode 0600 on POSIX), and no larger than 64 KiB. The route configuration is also parsed only after its file checks pass. When `BUILDER_EVIDENCE_GATEWAY_MODULE` is enabled, the host route/config JSON must include an operator-chosen `evidenceGatewaySecretRef`; the gateway factory receives `createEvidenceGateway({ readSecret, secretRef })` or may export a prebuilt `evidenceGateway`. It must implement only the read methods `fetchCommit(repository, sha, signal)`, `branchContainsCommit(repository, branch, sha, signal)`, `fetchWorkflowRun(repository, runId, signal)`, and `fetchWorkflowJobs(repository, runId, signal)`. The host exposes a frozen facade containing only those four methods. No live gateway module is configured in this repository/host, so correction updates currently stop with UNAVAILABLE rather than being recorded. A successful gateway check is source verification, not cryptographic attestation; Baldr's independent review remains a separate role requirement.

The encrypted Node vault now exposes `appendUserAdoption(taskReference, { runId, correctionId, authorization })`. It accepts only a short-lived Ed25519-signed `ADOPT_CORRECTION` assertion bound to the exact opaque user ID, task reference, run, thread, correction, nonce, and canonical issue time. The configured public-key map is supplied through `BUILDER_USER_AUTHORIZATION_PUBLIC_KEYS_JSON` or directly to `createEncryptedTaskVault({ userAuthorizationPublicKeys })`. Assertions expire after five minutes by default (30-second future clock skew), and a vault-wide encrypted nonce ledger rejects replay; an identical retry of the same accepted assertion is idempotent. The vault rechecks the correction history and accepts adoption only after the latest Baldr-authored VERIFIED stage. Adoption records project-practice acceptance only; they do not deploy, publish, or execute the correction.

This signature verifier does **not** authenticate a user interface session by itself. A separately trusted identity service must authenticate the user and issue the signed assertion; its private signing key must remain outside the vault, Builder prompts, and repository. No such identity provider or public key is configured in this repository/host. The user-action append method must be exposed only to the authenticated user-action host, not to the Builder executor. Vault mutations use a per-vault owner-only process lock in addition to an in-process queue. Concurrent writers wait within a bounded 30-second acquisition window; if the lock cannot be acquired, the mutation fails before writing. The scheduler's separate host lease still governs the scheduler and Builder-ledger state files. Keep the vault lock path on the same private filesystem as the encrypted vault; multi-host/network-filesystem coordination is not claimed. Builders cannot emit ADOPTED.

The adapter still passes through a validated operational-limits snapshot and defaults safely to `UNAVAILABLE`, but it does not independently recalculate per-capability evidence freshness or reconcile duplicate capability records. A configured live GitHub evidence gateway and held-out role-behavior tests remain outstanding. Do not treat the Node adapter as production-equivalent to the Android coordinator until those gaps are implemented and reviewed.

Provider calls use a 120-second default timeout, a 2,048-token output cap, a 1 MiB request/response limit, and redirect rejection. Cancellation is propagated through `AbortSignal`; the provider transport must honor it. Responses must be JSON chat-completion envelopes whose content is a valid Builder JSON reply. An `INTERRUPTION` or `IMPROVEMENT_INCIDENT` reply is durably saved and then stops the current round; the run ledger marks the active role and run as interrupted, and no later Builder is invoked. Results are persisted by the task vault before a non-resumable ledger checkpoint is written. An interrupted or uncertain call is not automatically replayed; manual reconciliation remains required.

CI tests use mocked HTTP responses and fake task/credential vaults. They verify route validation, role separation, cancellation, output validation, durable-checkpoint acknowledgement, and Baldr's final review gate. They do **not** verify real provider routes, real credentials, model identity, or unattended host execution. The example configuration uses `.invalid` endpoints and cannot make live calls.

## Tests

```sh
npm test
```

Tests cover schedule recurrence and daylight-saving transitions, GitHub failure
detection, persistent state, task controls, concurrency during handler execution,
dashboard access controls, process locking, and service startup/shutdown.


## Environment–Operations Boundary Protocol

The Nine Builders distinguish external environment conditions from their own governance and operations. A ChatGPT feature limit, provider quota, host restriction, recommendation, or warning must be scoped to the exact system and operation it affects; it must not silently become a collective prohibition. Binding legal, safety, security, consent, access-control, contractual, and authorization requirements remain binding and must not be bypassed.

See [the Environment–Operations Boundary Protocol](../docs/nine-builders-environment-operations-boundary.md) for constraint classifications, evidence requirements, Builder responsibilities, Kvasir's assessment sequence, interruption/resumption rules, and the Constraint Register record contract. The current operational-limits snapshot is not live telemetry; unavailable or unverified capability state remains unknown and requires an authoritative recheck. The document is a governance contract, not a claim that a persistent validated register or live telemetry feed already exists.
