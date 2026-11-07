# SCH-001 Nine Builders Witness-Consumption Boundary Review

Date: 2026-11-07
Status: PROPOSED DESIGN REVIEW — NOT IMPLEMENTED

## Decision

The existing Nine Builders runtime can be a scheduler/execution consumer of a future independent witness, but it cannot currently consume an SCH-001 witness decision. No witness client, witness receipt input, generation check, or external side-effect fence is wired into the runtime. The runtime must not be treated as an independent authority witness.

Decision remains: WAIT_FOR_INDEPENDENT_CUSTODY_EVIDENCE.

## Inspected source

Branch: `nine-builders-overnight-runtime-001`

- `scheduler/src/runner.js`: registers the optional Builder runtime and starts the local scheduler.
- `scheduler/src/builder-runner.js`: checks dedicated-runner configuration, then imports the shared runner.
- `scheduler/src/builder-runtime.js`: loads an operator-supplied executor and creates a local JSON-file Builder ledger.
- `scheduler/src/builder-run-handler.js`: creates/resumes Builder runs, invokes roles in order, records local checkpoints and completion.
- `scheduler/src/run-ledger.js`: local typed run metadata and Baldr invocation receipt validation.
- `scheduler/src/scheduler.js`: local task reservation, run-ID duplicate suppression, handler dispatch and interrupted-run handling.
- `scheduler/src/store.js`: local fsynced atomic JSON replacement.

## What the runtime already enforces (source-level observation)

1. The scheduler and Builder run ledger use separate state files.
2. The dedicated Builder runner requires separate state, lock and dashboard port from the general scheduler.
3. The Builder executor is host-supplied, not loaded from task payloads.
4. Run/task consent and provider route checks are delegated to the host vault and executor.
5. Interrupted or ambiguous provider calls are not automatically replayed.
6. Baldr completion requires an attributable invocation receipt matching its durable result.
7. Local process-lock loss aborts active handlers; local store writes use atomic replacement and fsync.
8. GitHub evidence lookup is read-only and mocked in CI; no live evidence gateway is configured in the repository/host.

These are useful local safeguards. They do not create independent custody or a cross-system fencing authority.

## Critical missing connection

The inspected execution path has no interface equivalent to:

- acquire or verify a witness-issued authorization for a specific task/run;
- bind that authorization to a witness generation, task digest, run ID, expiry/nonce and permitted side-effect scope;
- re-check current witness generation immediately before every external side effect;
- reject stale, absent, contradictory, replayed or unverifiable witness receipts;
- record an externally witnessed completion/uncertain outcome;
- prevent a scheduler or executor administrator from fabricating a witness decision.

The current local process lock is a same-host ownership signal, not a distributed fencing token. The local run ledger and encrypted task vault provide durable application records, not independent administrative custody. A successful Baldr invocation receipt establishes invocation attribution in the reference path, not governance independence.

## Safe target architecture (proposed only)

Separate three authorities:

- **Witness authority:** independently administered service issues monotonic, scoped authorization/deny/unknown decisions and durable receipts. It does not execute Builder roles.
- **Scheduler/executor:** requests permission, schedules work and runs approved handlers. It cannot mint, alter, reset or sign witness decisions.
- **Side-effect destination:** independently validates the current witness generation/permit at the operation boundary. A stale permit must not be accepted merely because the scheduler checked it earlier.

Suggested narrow interface:

```text
authorize(taskDigest, taskId, runId, requestedScope)
  -> { decision, witnessId, generation, receiptId, scope, expiresAt, signature }

verifyCurrent(permit, destination, operationDigest)
  -> { current, decision, generation, receipt }

recordOutcome(permit, outcomeDigest, outcomeClass)
  -> durable receipt or UNKNOWN
```

This is an interface sketch, not an implementation or selected protocol. Cryptographic formats, identity, key custody, transactional semantics and provider-specific destination enforcement remain to be designed and independently reviewed.

## Required fail-closed behavior

- No witness response, timeout, malformed receipt, signature failure, unknown key, stale generation, scope mismatch, task-digest mismatch, expiry, replay, rollback suspicion or contradictory result => BLOCK / QUARANTINE.
- A permit is not a durable completion receipt.
- A preflight check is not sufficient: destination-side fencing must occur at the side-effect boundary.
- If the destination cannot enforce the fence, classify that side effect as UNFENCED and do not claim independent protection.
- If an external call may have succeeded but its result cannot be witnessed, preserve UNKNOWN and require reconciliation; do not silently retry.
- Scheduler, executor, witness and destination must not share a single administrator or recovery path if they are being counted as independent domains.

## Test plan before adoption

1. Valid permit accepted only for exact task/run/scope/digest.
2. Expired, replayed, forged, wrong-key and wrong-scope permits rejected.
3. Generation N permit rejected after witness advances to N+1, including after scheduler restart.
4. Destination rejects a permit after witness outage or inability to establish current generation.
5. Scheduler administrator cannot mint a valid witness receipt.
6. Executor administrator cannot rewrite witness history or signing keys.
7. Witness restore/rollback/key rotation cannot silently lower generation.
8. Ambiguous external outcome remains UNKNOWN and is not replayed.
9. Competing authorizations for the same expected generation produce one valid successor.
10. Independent reviewer reproduces the tests using separately authenticated evidence access.

These are proposed tests. No witness integration or live destination-fencing test was performed.

## Evidence classification

- Source inspection: OBSERVED for the branch and files named above.
- Existing local safeguards: PRESENT IN SOURCE; prior CI evidence is limited to the tested commit and mock/local environment.
- Independent witness integration: ABSENT FROM INSPECTED EXECUTION PATH.
- Production deployment, independent custody and destination fencing: UNKNOWN / NOT VERIFIED.
- Adoption: NOT ADOPTED.

## Integrity boundary

This review does not provision resources, change credentials, alter scheduler tasks, activate Builder work, merge a pull request or transfer authority. It evaluates the existing source branch only. A later implementation must be a separate proposed change with adversarial tests and independent review.
