# SCH-001 — D1 witness prototype design and adversarial test plan

**Status:** proposed design/test specification only. No Cloudflare account, D1 database, Worker, credential, APK, handset setting, or live service was created or changed. No production authority or task activation is authorized.

## 1. Purpose and boundary

Test whether Cloudflare D1 can serve as a narrow transactional authority-event store for SCH-001. It is not a scheduler, task queue, general memory store, or proof that an external side effect completed.

Reuse the existing Nine Builders runtime for scheduling and execution. Use the Android application as an authenticated operator/status/recovery client. Keep the NUC deferred.

The prototype must fail closed: if the witness is unavailable, stale, conflicting, restored without continuity proof, or returns an ambiguous result, no transfer or destination activation is permitted.

## 2. Proposed topology

Nine Builders scheduler/executor
→ authenticated request with expected sequence and generation
→ Cloudflare Worker API
→ D1 authority head, append-only event history and receipt identity

Galaxy handset → operator approval, status and receipt inspection, recovery/reconciliation client. It receives no D1 administrative credentials and grants no authority offline.

An independent continuity checkpoint must be held outside the D1 account's restore and privileged administration domain.

## 3. Logical data model

### task_heads — mutable projection, not the history

One row per task:
- task_id (primary key)
- task_fingerprint
- sequence
- generation
- authority_owner
- latest_event_id
- latest_event_hash
- updated_at

The head is a convenience projection. It is never accepted as independent proof without matching the event history and external continuity checkpoint.

### authority_events — append-only history

One row per accepted event:
- event_id (globally unique stable idempotency key)
- task_id
- sequence
- generation
- event_type
- task_fingerprint
- expected_previous_sequence
- expected_previous_generation
- expected_previous_owner
- new_owner
- previous_event_hash
- event_hash
- authorization_principal
- authorization_decision_id
- authorization_evidence_digest
- transfer_id (nullable except transfer events)
- witness_timestamp
- schema_version
- receipt_id

Required uniqueness:
- primary/unique key on event_id
- unique (task_id, sequence)
- unique transfer_id when non-null, if one transfer maps to one authority transition

Accepted events are never edited or deleted by normal application identities. Corrections are new events linked to the event being corrected. Hash chaining is tamper evidence, not a substitute for access control or an independent trust anchor.

## 4. Conditional append — core transaction design

**Preferred prototype shape:** a single SQL INSERT into authority_events, guarded by a SQLite BEFORE INSERT trigger that checks the current task_heads row against the event's expected previous sequence, generation, owner and fingerprint. If any expectation fails, the trigger uses SQLite's abort mechanism so the insert fails. An AFTER INSERT trigger advances the matching task_heads projection in the same statement transaction. The event insert, trigger validation and head update must be atomic.

This is a design hypothesis to verify against D1's supported SQLite/trigger behavior before relying on it. Do not replace it with a Worker-side read-then-write sequence: two independent clients could both read the same old head. Do not treat a zero-row conditional update as a successful append.

Alternative if D1 trigger behavior or required constraints are unsuitable: use a single D1 transactional batch only if the batch can be made to abort when the conditional head advance affects zero rows. Demonstrate this property in a test; transaction batching alone does not prove compare-and-swap semantics.

### Request validation

Worker validates before the SQL append:
- authenticated principal and authorization for this task/transition;
- canonical payload and schema version;
- stable event ID and transfer ID;
- expected prior sequence, generation, owner and task fingerprint;
- permitted event transition and generation increment rule;
- event hash and previous-event linkage.

### Idempotency

On a unique event_id conflict:
1. Read the already stored event using the same ID.
2. Compare its canonical payload digest and all immutable transition fields with the retry.
3. If identical, return the original receipt and sequence.
4. If any field differs, reject as IDEMPOTENCY_KEY_REUSE_CONFLICT.
5. Never allocate a new event ID automatically after a timeout.

A timeout after submission is OUTCOME_UNKNOWN, not FAILED. Resolve by querying the original event ID.

### Transition rules

- Non-authority review/correction event: sequence increments by one; generation and owner remain unchanged.
- Authority transfer: sequence increments by one; generation increments exactly once; owner changes only after the source has quiesced, drained and explicitly relinquished.
- Prepare receipt alone does not transfer authority.
- Destination remains inactive until it verifies the committed generation and every relevant external side-effect boundary enforces that generation.
- No automatic failover on lease expiry or loss of contact.

## 5. Append-only and authorization boundary

Prototype safeguards:
- D1 binding and Worker credentials are server-side only.
- Scheduler and handset call authenticated Worker endpoints, never D1 administrative APIs.
- Normal application path exposes append/read operations, not event update/delete.
- Database triggers reject UPDATE and DELETE on accepted event rows for ordinary operation.
- Worker validates authenticated principal, authorization decision and transition scope.
- Secrets are never placed in source control, Android source, logs, or chat.
- Administrative/owner powers, account recovery, Worker deployment rights, backup restore rights and audit access are separately inventoried.

These controls do not make history absolutely immutable against a privileged account owner or provider-level administrator. That residual trust must be stated, not hidden.

## 6. Independent continuity and restore test

D1 Time Travel can restore an earlier database state. Therefore D1 cannot certify its own rollback continuity.

Before testing, export a checkpoint containing at least:
- task ID, sequence, generation, owner and task fingerprint;
- latest event ID and event hash;
- checkpoint creation time and schema version;
- an authenticated receipt/signature verifiable independently of the D1 database.

Keep the checkpoint outside the D1 database's ordinary restore and administration domain. For a meaningful production claim, it must also be outside the same Cloudflare account's privileged control/restore domain. A second file in the same D1 database, Worker, or account is not independent.

### Monotonic checkpoint protocol

The external anchor is a separate authority record, not a second copy of the D1 head. For each task it retains the highest accepted tuple: task fingerprint, authority generation, event sequence, latest event ID, latest event hash, and anchor receipt/sequence. An update is accepted only by an authenticated conditional append against the anchor's current version. The candidate must advance the task event sequence by exactly one, preserve or validly advance generation under the transition rules, and link its previous-event hash to the anchor's current event hash. Lower sequence/generation, same-sequence conflicting hash, skipped sequence, wrong task fingerprint, or stale anchor version is rejected. The anchor's own receipt sequence is strictly increasing and cannot be reset by restoring D1.

The local D1L-14 harness models only the comparison and monotonicity rules: the Node test-runner process retains a checkpoint outside the Worker/D1 process, advances it from event sequence 1 to 2 after checking hash linkage, rejects an attempt to replace it with the older checkpoint, then detects a coordinated rollback of both D1 event history and head projection. This is a process-memory fixture on the same host, not a durable or separately administered anchor. D1L-15 adds a local durable-process-restart fixture: a separate loopback Node process appends checkpoint records to a hash-chained JSONL journal using file synchronization, reloads and verifies that journal after process restart, and detects a coordinated D1 rollback after the D1 Worker is also restarted. The test's journal lives in a temporary directory on the same CI host and is deleted after the run; it is not an independent administrative or security domain. A real independent witness must implement authenticated durable conditional append and custody outside the D1 account's privileged restore domain; those properties remain unimplemented and unverified.

Prototype restore test:
1. Append several test events and capture the latest checkpoint/receipt outside D1.
2. Restore D1 to a point before the latest acknowledged event.
3. Read the restored database and compare it with the external checkpoint.
4. Require WITNESS_ROLLBACK_OR_LOSS; freeze authority changes and activation.
5. Preserve the restored state and evidence; do not silently replay or recreate missing events.
6. Recover only through a separately reviewed continuity procedure and new linked corrective events.

A manually held checkpoint is useful for a first controlled experiment but is not proof of an automated production recovery system.

## 7. Adversarial acceptance tests

| ID | Attack / failure | Required result |
|---|---|---|
| D1-01 | Two independent clients append from the same expected sequence/generation | Exactly one accepted; the other receives stale/conflict; one head advance only |
| D1-02 | Stale sequence | Rejected; no event and no head change |
| D1-03 | Stale generation or wrong current owner | Rejected; no event and no head change |
| D1-04 | Wrong task fingerprint | Rejected; no event and no head change |
| D1-05 | Same event ID, identical payload retry | Original receipt returned; no second event |
| D1-06 | Same event ID, altered payload | Rejected as idempotency-key reuse conflict |
| D1-07 | Commit succeeds but response is lost | Client reports outcome unknown, queries same event ID and recovers original receipt |
| D1-08 | Trigger validation fails during append | Entire statement rolls back; neither event nor head changes |
| D1-09 | Batch has a later failing statement | Verify all earlier statements roll back; do not infer this proves CAS by itself |
| D1-10 | Ordinary client attempts event UPDATE/DELETE | Denied; accepted event remains unchanged |
| D1-11 | Privileged restore rewinds D1 | External checkpoint detects lower sequence/hash; quarantine |
| D1-12 | Free-tier read/write limit or service unavailable | No authority change or destination activation; status clearly unavailable |
| D1-13 | Worker secret absent/invalid or principal unauthorized | Denied; no write |
| D1-14 | Worker/phone replays cached old head while offline | Display stale/offline; no transfer approval or activation |
| D1-15 | Android process death, reboot, Doze or duplicate tap | No duplicate event; query original event ID; no offline authority grant |
| D1-16 | Source relinquishes but response is lost | Query same transfer ID; destination stays inactive until witness result and fencing are verified |
| D1-17 | D1 restored and its local event/head agree with each other but external checkpoint is newer | Detect rollback; internal consistency is insufficient |
| D1-18 | Hash chain is recomputed by a privileged actor after tampering | Independent checkpoint/signature mismatch detects alteration, or test documents that this threat remains unmitigated |\n| D1-19 | Journal bytes are corrupted or its final record is truncated | Witness refuses startup; no checkpoint is served from malformed history |\n| D1-20 | A previously valid older journal copy replaces the current journal | Internal replay may succeed; comparison with a separately retained latest receipt detects rollback |\n| D1-21 | Privileged actor rewrites the full journal and recomputes every record hash | Local replay may succeed; a receipt outside that actor's control detects the substituted checkpoint |

For every test record: test ID, environment/version, setup, exact inputs, expected result, actual result, event/head before and after, receipt, logs/evidence digest, reviewer, and status (PROPOSED, RUN, PASS, FAIL, BLOCKED). Do not label a test PASS without retained output.

## 8. Android role and device identity

The existing repository describes a Galaxy S21, but the exact physical handset/model and current app installation are not independently confirmed in this reassessment.

Android should initially provide:
- authenticated operator approval;
- read-only authority status and receipt display;
- recovery evidence capture;
- notifications for unavailable, stale or conflicting witness states.

Android must not:
- treat local SQLite as the authority witness;
- grant authority while offline;
- infer success from a notification or timeout;
- replay non-idempotent work without reconciliation;
- store cloud administrator credentials in the app.

Before device testing, confirm the handset model and inspect the existing Android prototype's build/test state. No installation is implied by this design.

## 9. Prototype phases and stop conditions

1. Static design review: confirm schema, transition table, authentication and trigger semantics.
2. Local disposable D1-compatible test: validate trigger/transaction behavior without real tasks or credentials.
3. Cloud free-tier sandbox: only after explicit user approval; synthetic task IDs and no external side effects.
4. Adversarial run: execute D1-01 through D1-18 and retain evidence.
5. Independent review: inspect implementation, access control, rollback test, export and restore procedure.
6. Adoption decision: separate explicit decision. Until then, all authority transfer and task activation remain disabled.

Stop if trigger semantics are not atomic, if competing writers can both succeed, if acknowledged writes can disappear without external detection, if the continuity anchor shares the same privileged restore domain, or if an external side-effect boundary cannot enforce fencing.

## 10. Integrity and status

**Proposed:** data model, trigger-guarded append hypothesis, API boundary, recovery checkpoint and 18 adversarial tests.

**Implemented in local prototype:** D1-compatible schema/trigger path, test-only Worker API, local durable checkpoint journal, process-restart test, and D1L-16–D1L-19 simulations for corruption, truncation, older-copy rollback and privileged full-chain rewrite. The latter tests use a receipt held by the same CI runner, not an independent security domain.

**Not yet implemented:** production authentication, independently administered remote checkpoint service, remote D1 database, Android integration or production recovery/fencing.

**Not tested or verified:** production D1 trigger support/behavior for this exact append design, distributed competing-writer semantics, production durability contract, actual provider restore, account separation, free-tier failure behavior, Android lifecycle behavior, or defense against compromise of the external receipt holder.

**Not adopted:** Cloudflare D1 as production witness; no provider or paid tier selected.

**Unchanged:** Nine Builders runtime, Android installation/settings, NUC, credentials, cloud accounts, external side-effect systems and task activation.
