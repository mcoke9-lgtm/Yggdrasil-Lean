# SCH-001 — Independent recovery witness design

**Status:** proposed architecture and recovery contract. Not implemented, deployed, independently reviewed, or adopted. This document does not authorize task activation or automatic failover.

## 1. Root requirement

A scheduler backup may restore task and run data. It must not, by itself, restore the right to exercise execution authority.

The authority history needs a separately controlled monotonic witness. The witness must retain the latest accepted authority generation even when the scheduler host, its state file, and its backups are rolled back together.

**Fail closed:** if the witness is unavailable, stale, inconsistent, or cannot prove the expected generation, do not advance authority, activate a transferred task, or resume a disputed owner. Preserve evidence and enter recovery quarantine.

## 2. Separation of responsibilities

| Component | Responsibility | Must not claim |
|---|---|---|
| Scheduler state | Tasks, runs, handoffs, local authority projection | Independent truth about latest authority |
| Local integrity sidecar | Detect local byte corruption and one-sided replacement | Protection against coordinated backup rollback |
| Recovery witness | Durable, monotonic authority sequence and transfer decisions | Proof that external side effects completed |
| Execution boundary | Reject stale authority generations before effects | Authority based only on an unverified local file |
| Reviewer/operator | Reconcile ambiguous history and authorize recovery | Ability to rewrite witness history silently |

The witness is a narrow authority ledger, not a second scheduler, task queue, or general-purpose memory database.

## 3. Minimum witness properties

A production candidate must provide all of these properties, with evidence:

1. **Independent failure domain:** not stored in the scheduler state directory, host image, or same backup/restore set. Prefer separate administrative credentials and separately managed backup policy.
2. **Monotonic conditional append:** accept a new authority event only when its expected previous sequence and authority generation match the current witness record. Competing writers cannot both succeed.
3. **Durable acknowledged receipt:** acknowledge only after the event is committed under the service's documented durability contract. A timeout is an unknown outcome, not a failed write.
4. **Immutable history:** no ordinary client update/delete or restore operation can rewrite accepted events. Corrections are new, linked events.
5. **Stable idempotency:** retries use the same event/transfer ID and receive the original receipt; changed content under the same ID is rejected.
6. **Authenticated authorization:** authenticate the caller and verify that the requested actor is authorized for this task and transition. A hash alone is not authorization.
7. **Verifiable recovery:** after service restart or disaster recovery, the service can prove its latest sequence and identify any loss of acknowledged writes. Its own recovery process must not silently rewind the witness.
8. **Observable failure:** unavailable, stale, conflicting, or unverifiable witness state is distinguishable and causes a fail-closed result.

A transactional database is sufficient only if its conditional transaction, durable commit acknowledgement, access controls, backup/restore, and rollback recovery satisfy these properties. A consensus-backed service is appropriate when multi-host authority changes during partitions are required. A single-host file or database on the scheduler host is not an independent witness.

## 4. Witness record

One logical record per task, with an append-only event history:

- task ID;
- immutable task-definition fingerprint and definition version;
- witness sequence number;
- authority generation (epoch);
- current authority owner;
- previous event hash and current event hash;
- event ID and transfer ID, where applicable;
- event type and transition;
- source and destination scheduler IDs;
- expected previous sequence, generation, and owner;
- authorization principal, decision ID, and authorization evidence reference;
- timestamp supplied by witness, plus caller timestamp as untrusted context;
- witness commit/receipt ID and durability class;
- schema version.

The witness sequence and authority generation are separate values. A non-authority event (for example, a reviewed correction) can append to the history without transferring execution ownership. A transfer increments the authority generation exactly once.

The hash chain improves tamper evidence but is not, by itself, an independent trust anchor. The service's access controls, append semantics, durable history, and externally verifiable receipts remain essential.

## 5. Transfer protocol

1. **Prepare:** source submits a stable transfer ID, task fingerprint, expected witness sequence/generation/owner, proposed destination, and explicit authorization.
2. **Conditional append:** witness atomically checks all expected values and records TRANSFER_PREPARED. A duplicate identical ID returns the original receipt; changed content or stale expectations are rejected.
3. **Quiesce and drain:** source stops admitting new work for that task and records/drains in-flight operations. This is a source-side action, not something a witness receipt proves.
4. **Relinquish:** source submits evidence that it has relinquished the task. The witness appends SOURCE_RELINQUISHED only after validating the transition and required evidence.
5. **Advance authority:** a conditional witness append increments the generation and assigns the destination. The durable receipt is the authority decision; a local coordinator grant is not.
6. **Install fencing:** every side-effect boundary used by the handler must reject requests carrying an older generation. If even one relevant boundary cannot enforce fencing, activation remains blocked.
7. **Activate:** destination may activate only after it has independently read/verified the committed witness generation, installed the matching fence at all required boundaries, and validated the task definition against the witness fingerprint.

This protocol does not imply that all external systems support fencing. If a handler writes to an external service that cannot check the generation or enforce an idempotency key, that handler is not eligible for automatic cross-host transfer.

## 6. Recovery and reconciliation

On startup or restore:

1. Preserve the state file, integrity sidecar, backups, lock, logs, and relevant external-effect evidence before modifying anything.
2. Read and authenticate the witness using a read-only recovery identity.
3. Compare local task fingerprint, authority owner/generation, transfer IDs, event sequence, and latest witness receipt with the witness history.
4. Classify the result:
   - MATCH: local projection agrees with the witness; continue only after normal operational checks.
   - LOCAL_BEHIND: witness is newer; keep task inactive and reconstruct the local projection from verified witness events.
   - LOCAL_AHEAD: local state claims an event the witness cannot confirm; quarantine. Never promote local state to the witness automatically.
   - CONFLICT: owner, fingerprint, generation, or event identity differs; freeze both claims and require independent reconciliation.
   - WITNESS_UNAVAILABLE: preserve current state and stop authority changes and transferred-task activation.
   - WITNESS_ROLLBACK_OR_LOSS: witness cannot establish continuity of acknowledged history; suspend authority operations and invoke the witness provider's own disaster-recovery process.
5. Query uncertain writes by their original event/transfer ID. Do not create a new ID or blindly retry a transfer.
6. Reconcile external side effects separately. A witness proves accepted authority events, not whether an email, payment, report, or other effect occurred.
7. Record reviewer, evidence, decision, and any corrective append. Never edit old witness events in place.

A restored scheduler backup must never be used to initialize or reset the witness. A witness restored from its own backup must prove continuity from an independent checkpoint or enter its own quarantine.

## 7. Failure matrix

| Failure | Required behavior |
|---|---|
| Witness unavailable before prepare | No transfer; source retains current authority if its existing authority remains independently valid |
| Prepare receipt lost | Query same event ID; do not create a second transfer |
| Source crashes before relinquishment | Destination remains inactive; reconcile source and in-flight effects |
| Source relinquishes but response is lost | Query witness by same transfer ID; do not infer success from timeout |
| Witness advances but destination cannot install a fence | Destination remains inactive; do not roll back by assigning the old generation |
| Old source resumes after generation advance | External boundary rejects stale generation; if it cannot, automatic transfer was not safe |
| Scheduler backup is older than witness | Local projection is quarantined and rebuilt from verified events |
| Witness history is unavailable or rolled back | No authority changes or task activation until witness continuity is restored and independently verified |
| External effect outcome is unknown | Mark outcome unknown; use handler operation class and reconciliation policy, not blind replay |

## Existing project infrastructure inventory

Before selecting a provider, consult [SCH-001 Existing Infrastructure Inventory](SCH-001-existing-infrastructure-inventory.md). It identifies the existing Nine Builders Node runtime, Android host prototype, NUC candidate, GitHub Actions, and Airtable pilot, and records why none currently satisfies the independent witness gates. Reuse existing runtime components; do not duplicate them or provision a new service until the host and custody inventory is verified.

## 8. Candidate foundation decision — deliberately open

Do not choose a provider or add infrastructure solely to escape the ChatGPT task-slot limit.

Evaluate in this order:

1. **Existing independently administered transactional database:** preferred if it supports atomic conditional append/CAS, durable acknowledgements, immutable/auditable history, access separation, and documented restore continuity.
2. **Existing consensus-backed authority service:** required if the operating requirement includes multi-host automatic authority changes during network partitions. Quorum loss must stop authority changes.
3. **Single-host local coordinator:** remains useful for development and manual recovery, but is not an independent rollback witness and must never authorize automatic cross-host failover.

Before selection, identify the actual service, owner, administrators, credential boundary, durability and restore guarantees, retention, audit export, availability objectives, and cost. No provider, database, consensus cluster, credential, or new institution is selected here.

## 9. Acceptance gates before implementation

- A named witness service and independent administrative/failure domain are documented.
- Conditional append and same-ID retry semantics are demonstrated under competing writers.
- Acknowledged history survives the provider's documented failure and restore scenarios.
- A paired rollback of scheduler state and local sidecar is detected by comparison with the witness.
- Witness rollback or loss is detectable; the recovery process cannot silently self-certify.
- Authorization and event authenticity are verified.
- All relevant external side-effect boundaries enforce the current generation, or the handler is explicitly excluded from automatic transfer.
- Failure-injection tests cover every row in the matrix.
- Independent reviewer examines the design, implementation, restore procedure, and evidence.
- Explicit adoption decision is recorded before any activation or deployment.

## 10. Current status and boundary

**Proposed:** witness schema, transfer protocol, reconciliation states, failure matrix, and acceptance gates in this document.

**Implemented:** local JSON integrity sidecar and fail-closed local checks from the preceding increment.

**Tested:** local sidecar corruption, one-sided replacement, old-state/current-sidecar mismatch, and the paired-rollback limitation. These tests do not test an independent witness.

**Not implemented or verified:** remote witness, independent monotonic sequence, witness authentication, append-only provider, production fencing, provider restore continuity, automated reconciliation, task activation, or automatic failover.

AuthorityCoordinator remains LOCAL_REFERENCE_GRANT_ONLY. SCH-001 remains a development-branch prototype. No merge, deployment, or adoption is authorized by this design document.
