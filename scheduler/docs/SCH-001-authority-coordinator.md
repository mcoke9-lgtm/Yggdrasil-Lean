# SCH-001 — Authority coordinator interface and local CAS reference

**Status:** development-branch reference implementation and contract; not distributed, not adopted, and not an activation mechanism.

## Required coordinator interface

A production coordinator must provide these semantics:

- `initialize(taskId, owner, taskDefinitionFingerprint)`: create a unique authority record once.
- `compareAndSwap(taskId, transferId, expectedEpoch, expectedOwner, newOwner, taskDefinitionFingerprint)`: atomically compare the complete expected authority identity and advance the epoch by exactly one.
- `get(taskId)`: read the authoritative current record.
- `getTransfer(transferId)`: recover the original result after a timeout or lost response.

The coordinator must durably serialize competing updates, enforce globally unique stable transfer IDs, reject transfer-ID reuse with changed content, bind each grant to an immutable task-definition fingerprint, and never reuse or decrease epochs. A successful response must be recoverable by transfer ID after client retry or coordinator restart.

## Local reference implementation

`src/authority-coordinator.js` implements the interface against the scheduler store. State version 3 adds:

- `authorities`: current owner, epoch/version, task-definition fingerprint, and last transfer ID.
- `authorityTransfers`: append-only transfer receipts with source, destination, prior/granted epochs, request fingerprint, and timestamp.

Version 1 and 2 state are migrated in memory to version 3 while preserving existing task, run, and handoff records. The next successful save persists the new envelope.

The reference coordinator serializes operations through the shared store-instance lock. It provides local durable read-modify-write semantics for a single process and a single shared store object. The compare-and-swap check and state write occur within that critical section.

## Explicit non-guarantees

This implementation is **not** a distributed authority coordinator. It does not provide:

- Cross-process or cross-host mutual exclusion.
- Linearizable consensus, quorum durability, or split-brain prevention.
- Rollback-resistant monotonic epochs after arbitrary backup restore.
- Cryptographic authentication or verification of callers.
- Source quiescence, in-flight drain, or relinquishment proof.
- Fencing at any external side-effect boundary.
- Destination activation or safe automatic failover.

The returned marker `LOCAL_REFERENCE_GRANT_ONLY` is deliberately not an execution credential. A production scheduler must reject it as insufficient authority. Do not connect this reference coordinator to task activation.

## Acceptance tests

The reference tests cover initialization idempotency, conflicting initialization, single-step epoch advancement, stale compare-and-swap rejection, stable transfer-ID replay, changed-request rejection, concurrent competing updates, task fingerprint binding, restart recovery, and version-2 migration.

A passing unit test demonstrates the local reference semantics only. It cannot establish distributed safety. A production implementation requires an independently reviewed coordinator with a documented consistency model, fault-tolerant storage, monotonic recovery, authenticated callers, and effect-boundary fencing tests.