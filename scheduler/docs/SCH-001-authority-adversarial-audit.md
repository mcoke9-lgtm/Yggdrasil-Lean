# SCH-001 — Authority coordinator adversarial recovery audit

**Audit status:** conceptual and deterministic synthetic tests committed to the development branch. This is not an independent review, distributed-systems certification, or approval for activation.

## Scope

Reviewed the local authority reference implementation against lost responses/retries, separate store clients, process restart/crash boundaries, and stale-backup restoration. The objective is to identify actual guarantees, not infer distributed safety from single-process tests.

## Findings

### AUD-AC-01 — Separate store instances do not provide compare-and-swap isolation

**Severity:** Critical for any multi-process or multi-host use.
**Observed:** `withStoreLock` keys its queue by JavaScript store-object identity. Two `JsonFileStore` objects pointing at the same path have different locks and write queues. Two processes necessarily have independent locks.
**Synthetic test:** `authority-coordinator-adversarial.test.js` forces two independent store clients to read the same epoch before either writes. Both compare-and-swap calls return success for epoch 2, while the last whole-document write erases the other transfer receipt.
**Disposition:** Confirmed design limitation; fail closed for multi-writer use. A passing test here documents unsafe behavior; it does not mean the behavior is acceptable.
**Required correction:** use a storage primitive that enforces conditional update/transaction at the durable authority record, or a consensus-backed coordinator. A process-local mutex, atomic file rename, or application-level read-then-write is insufficient.

### AUD-AC-02 — Backup rollback can reuse a previously granted epoch

**Severity:** Critical for any system treating epochs as fencing tokens.
**Observed:** authority epoch and transfer history are stored in the same restorable JSON snapshot. Restoring an older snapshot restores the old epoch and permits a different transfer to grant the same next epoch.
**Synthetic test:** restore a pre-transfer snapshot after epoch 2 was granted; the local coordinator then grants epoch 2 to a different destination.
**Disposition:** Confirmed design limitation.
**Required correction:** epochs require a non-rollbackable monotonic witness or consensus-backed durable log with a defined restore/rejoin procedure. Backups must not silently roll authority history backward.

### AUD-AC-03 — Lost response after durable save is recoverable only while the same authoritative store remains intact

**Severity:** High.
**Observed:** stable transfer IDs allow a retry to return the prior receipt if the write completed and the same state remains available.
**Limit:** this does not protect against split-brain writers, lost/rolled-back storage, or a response lost before the durable write. A caller must query by transfer ID against the authoritative coordinator; it must not create a new transfer ID merely because a response timed out.
**Disposition:** Local retry behavior supported; distributed recovery unverified.

### AUD-AC-04 — Process crash and file replacement are not a complete durability proof

**Severity:** High for production authority.
**Observed:** temporary-file write followed by rename is a useful local persistence pattern. The current implementation does not demonstrate directory/file fsync durability across power loss, cross-process serialization, unique temp names per concurrent writer, or crash-safe recovery of partially completed coordinator operations.
**Disposition:** Not proven. Existing restart tests cover orderly reconstruction after a completed save, not power-loss or concurrent process crashes.
**Required correction:** define the storage durability contract, unique transaction temp objects, fsync/commit semantics where required, and crash-injection tests on the selected storage engine.

## Recovery rules

1. A timeout or lost response is an unknown result, not a failed grant. Query using the original stable transfer ID.
2. If the authoritative store is unavailable or its history may have rolled back, stop authority changes and keep the destination inactive.
3. If two writers or two different owners report the same epoch, mark authority state conflicted; do not choose the newest timestamp or reachable host as a tie-breaker.
4. Never repair a split-brain epoch by editing JSON manually. Preserve snapshots and receipts, then reconcile through a reviewed recovery procedure.
5. A restored backup must enter quarantine until its epoch/history is reconciled with a non-rollbackable authority source.
6. Do not treat local `AUTHORITY_ADVANCED` or `LOCAL_REFERENCE_GRANT_ONLY` as an external fencing credential.

## Required production architecture boundary

A production coordinator needs durable conditional writes/transactions or consensus, authenticated principals, a monotonic authority history resistant to rollback, a documented quorum/partition policy, and fencing enforcement at every side-effect boundary. The current JSON file store cannot supply these properties.

## Test interpretation

The ordinary authority unit tests demonstrate single-process local reference behavior. The adversarial tests intentionally reproduce unsafe outcomes under separate writers and backup rollback. Their success means the vulnerability is reproducible and documented—not that the coordinator passed a distributed-safety test.

## Adoption boundary

No activation, automatic failover, external fencing, production coordinator, merge, or deployment is authorized or implemented by this audit.