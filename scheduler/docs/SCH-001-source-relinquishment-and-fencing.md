# SCH-001 — Source relinquishment and execution-fencing contract

**Status:** proposed safety contract; documentation only; not implemented or adopted.

## 1. Purpose and governing rule

Define the minimum evidence and enforcement needed before a destination scheduler may receive authority to execute a handed-off task.

**A destination must not activate a task merely because the source is unreachable, a heartbeat is missing, a lease appears expired, a receipt exists, or the source reports that it has stopped.**

Exclusive authority is established only when the system that accepts the task's externally visible side effects enforces a newer authority generation and rejects stale generations. If a handler's side-effect boundary cannot enforce that rule, safe automatic transfer is unavailable for that handler.

## 2. Distinct actors and responsibilities

- **Source scheduler:** stops creating new reservations for the task, records its relinquishment intent, drains or classifies in-flight work, and retains evidence.
- **Destination scheduler:** validates and accepts the immutable task definition but remains inactive until authority transfer is proven.
- **Authority coordinator:** provides a linearizable, durable compare-and-swap operation for task authority generations. It must not issue conflicting current generations.
- **Effect boundary:** the system that performs or commits the actual side effect. It must validate the authority generation at the point of effect, not merely at scheduler startup.
- **Reconciler:** resolves incomplete transfers and uncertain outcomes without guessing or silently replaying operations.

A scheduler-local JSON file, process lock, heartbeat, or inbox receipt is not an authority coordinator or an effect-boundary fence.

## 3. Authority record

A future authority record should include at least:

- stable task identity and immutable task-definition fingerprint/version;
- current authority owner (scheduler identity);
- monotonically increasing authority epoch/generation;
- transfer identifier and source/destination identities;
- transfer state and compare-and-swap version;
- issuance time and coordinator evidence;
- prior generation and successor generation;
- effect-boundary identity or set of boundaries required to enforce the generation.

Epochs must be monotonic and never reused for a task, including after restore or coordinator recovery. A task-definition change must not silently inherit an unrelated authority grant.

## 4. Transfer state machine

The proposed transfer sequence is:

1. **HANDOFF_ACCEPTED** — destination has explicitly accepted the validated handoff; it has no execution authority.
2. **SOURCE_QUIESCING** — source durably blocks new reservations for this task and records the transfer ID and current authority epoch.
3. **SOURCE_DRAINING** — source waits for in-flight handlers to finish or records each unresolved operation as uncertain. It must not label a handler stopped while it may still produce effects.
4. **SOURCE_RELINQUISHED** — source records that it will issue no further work under the old epoch. This is necessary evidence, but is not alone sufficient to authorize the destination.
5. **AUTHORITY_ADVANCED** — the authority coordinator atomically advances the task to a strictly newer epoch and assigns it to the destination. The coordinator returns a durable, verifiable grant tied to the transfer and task-definition fingerprint.
6. **DESTINATION_FENCED_READY** — every required effect boundary confirms it will reject requests with epochs older than the new epoch, or provides an equivalent enforceable fence.
7. **TASK_ACTIVATED** — only now may the destination schedule and dispatch work under the new epoch.

The source must be unable to create effective side effects after step 5 because all relevant effect boundaries reject its stale epoch. The source's local state is still retained for audit and recovery.

If a required effect boundary cannot enforce the epoch, the transfer stops before activation. It remains inactive for manual reconciliation or a different explicitly authorized recovery path.

## 5. In-flight work and uncertain outcomes

A scheduler cannot infer that an external action did not happen just because its local run record is missing, interrupted, or unacknowledged.

- A completed operation requires evidence from its effect boundary or a reliable idempotency record.
- An operation with unknown outcome becomes **EXECUTION_OUTCOME_UNKNOWN**.
- Read-only operations may be retried only under their declared retry policy.
- Naturally idempotent operations require a state check.
- Deduplicatable operations require the external system to enforce a stable operation key.
- Irreversible/non-idempotent operations remain stopped for reconciliation or fresh explicit authorization.
- Compensation is a separate corrective action, not a reversal, and requires its own authorization and evidence.

A transfer may advance only after all in-flight operations are either proven complete, safely deduplicated, or explicitly carried as unresolved work that cannot be replayed by the destination.

## 6. Partition, timeout, and recovery behavior

| Condition | Required behavior |
|---|---|
| Source unreachable before relinquishment is proven | Destination remains inactive |
| Heartbeat missing or lease timeout | Do not infer relinquishment |
| Coordinator unavailable | Do not issue a new authority epoch |
| Coordinator response lost | Query by stable transfer ID; do not create a competing transfer |
| Destination receipt lost | Query the inbox by handoff ID; do not blindly create a new handoff |
| Source resumes with old epoch | Effect boundary rejects stale epoch |
| Fence cannot be confirmed at one required effect boundary | Do not activate |
| In-flight effect outcome unknown | Preserve uncertainty; no blind replay |
| Coordinator restored from backup with possible epoch rollback | Suspend transfers until monotonicity is independently re-established |

Safety takes priority over availability. During uncertainty, preserve accepted-but-inactive state and the evidence needed to resume reconciliation.

## 7. Reacquisition and cancellation

A source that wants authority again must obtain a strictly newer epoch through the same coordinator and effect-boundary fencing process. It must never resume under its previous epoch merely because the destination is unreachable.

Cancellation during transfer is a separate control operation. It must use a stable command ID and monotonic control generation, be recorded at both schedulers, and be reconciled against the authority coordinator. Cancellation does not itself prove that an already-running external effect was undone.

## 8. Required implementation gates

Do not implement destination activation until all of the following exist and are tested:

1. A durable authority coordinator with atomic compare-and-swap and monotonic epochs.
2. An effect-boundary fencing contract that validates epochs at the point of every relevant side effect.
3. A source quiescence gate that blocks new reservations before draining.
4. In-flight operation inventory and EXECUTION_OUTCOME_UNKNOWN handling.
5. Transfer IDs, idempotent coordinator queries, and durable receipts for each transition.
6. Crash recovery at every transition boundary without inventing success.
7. Partition and stale-source adversarial tests.
8. Independent review of the fencing implementation and its actual side-effect boundaries.

If a task handler reaches multiple external systems, every system capable of committing a relevant effect must participate in fencing or the task must remain ineligible for automatic transfer.

## 9. Adversarial acceptance matrix

The implementation must eventually demonstrate:

- source process crashes before recording quiescence;
- source crashes after quiescence but before drain completion;
- source loses coordinator response after epoch advancement;
- destination receives an old or duplicated grant;
- source resumes after destination activation and attempts an old-epoch effect;
- one of multiple effect boundaries fails to install the new fence;
- coordinator restore attempts to reuse or decrease an epoch;
- transfer is cancelled while a handler is in flight;
- handler commits an effect but its completion response is lost;
- destination restarts after activation but before its first run record is committed.

For every case, the test must assert both the recorded state and whether the external effect boundary would accept or reject the operation. State-only tests cannot prove fencing.

## 10. Current prototype boundary

The current SCH-001 branch has a durable inbox, destination validation, explicit acceptance, and a process-local shared-store lock. It does not have a source relinquishment protocol, authority coordinator, distributed epoch, or side-effect fencing. Its handler context does not yet carry a verified authority grant.

Therefore:

- HANDOFF_ACCEPTED remains accepted-but-inactive.
- No TASK_ACTIVATED transition is authorized by this document.
- The local process lock only coordinates objects sharing one store instance in one process.
- No claim of distributed single-active execution or safe automatic failover is made.

This document is a proposed contract for the next implementation design review, not evidence that the requirements have been implemented or independently verified.
