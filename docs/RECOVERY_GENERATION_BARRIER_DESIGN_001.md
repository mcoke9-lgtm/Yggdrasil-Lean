# Recovery Generation Barrier Design — 001

Status: proposed design; not implemented; not wired to startup.

## Purpose

Prevent conversation and subject writers in separate Android processes from observing or writing against different recovery generations during activation, rollback, restart, or recovery. Preserve immutable staged baseline databases and their exact record-array SHA-256 digests.

## Current verified facts

- `BuilderRecoveryActivation` publishes a generation descriptor and switches one active pointer using a same-directory atomic rename with file and directory sync.
- Its `activate`, `getActive`, and `rollback` methods use Java `synchronized`, which coordinates threads in one process only.
- Conversation writes are owned by `BuilderRuntimeService` in the `:builder_runtime` process; subject-event writes are currently initiated from the activity process.
- `BuilderRecoveryOverlayStore` uses one SQLite database for both post-baseline streams and binds itself to generation ID plus both baseline digests.
- The overlay is not wired into application startup or the live stores.

## Proposed primitive: one app-private exclusive OS file lock

Use a permanent lock file in the app-private files directory, for example `builder-recovery-generation.lock`.

Every operation that resolves or uses the active generation must acquire the same exclusive OS-level file lock and hold it across the complete critical section:
1. Read and validate the active pointer and generation descriptor.
2. Verify the selected baseline and overlay identities.
3. Open or validate the selected stores.
4. Perform the requested read, append, or paired export.
5. Commit any SQLite transaction and close temporary handles.
6. Release the OS lock.

Use `FileChannel.lock()` (exclusive) for the first implementation. Do not rely on shared locks or filesystem-specific shared-lock semantics. SQLite remains responsible for its own database transaction integrity; the file lock coordinates the multi-file generation boundary across Android processes.

Within one process, guard acquisition with one static process-wide `ReentrantLock` so two threads do not trigger Java's overlapping-file-lock exception. Acquire the JVM lock before the OS file lock and release in reverse order. Do not delete or replace the lock file. Handle interruption and I/O failure as fail-closed errors; never continue without the lock.

The initial exclusive lock serializes even reads. This is a deliberate conservative trade-off. Shared/read locks can be considered only after actual Android multi-process tests prove their semantics on supported devices and filesystems.

## Required operation rules

### Read / append / export
- Acquire the barrier before resolving the active generation.
- Re-read the pointer while holding the barrier; do not trust a generation cached before lock acquisition.
- Compare the selected generation identity with the store handle being used. If it differs, close/reopen from the selected generation or fail with a diagnosable stale-handle error.
- Hold the barrier through the entire operation, including SQLite commit.
- Conversation and subject appends both target the same generation-bound overlay file.
- Paired export holds the barrier and uses one overlay SQLite read transaction for both streams.
- No caller may bypass the barrier by retaining and using a raw overlay helper outside a guarded operation.

### Activate
- Acquire the exclusive barrier before reading current active state.
- Validate the staged immutable baseline pair and their exact digests.
- Create or validate the target generation's overlay before publishing the pointer. An existing overlay must match the exact generation and both baseline digests; never reset it.
- Persist and fsync the immutable generation descriptor.
- Write and fsync the temporary pointer; atomically rename it over the active pointer; fsync the parent directory.
- Release the barrier only after publication is durable or an error is reported.
- If a crash occurs before pointer rename, the previous pointer remains authoritative. If it occurs after rename, the new pointer refers only to already-complete baseline and overlay files.

### Rollback
- Acquire the same barrier.
- Validate the target generation's baseline pair AND its matching overlay. A baseline-only generation is not rollback-ready.
- Publish the pointer atomically using the same durability sequence.
- Never delete or rewrite either generation's databases.

### Startup / process recovery
- Startup must not cache a generation identity outside the guarded store abstraction.
- If the lock cannot be acquired, the pointer/descriptor is invalid, the overlay is missing/corrupt, or any identity mismatches, fail closed with an actionable diagnostic. Do not fall back to canonical stores or a different generation silently.

## Lock ordering

1. Process-local reentrant lock.
2. OS file lock.
3. Active pointer / descriptor file operations.
4. SQLite database operations and transactions.

All code paths must use this ordering. No code may acquire the process lock while already holding a SQLite transaction or OS lock. Keep the lock scope narrow but complete.

## Acceptance tests

1. Two independent helper instances in one process serialize guarded operations and observe one generation.
2. A second Android process blocks while the first holds the barrier; it proceeds after release.
3. Activation waits for an in-flight append to commit; the append lands wholly in the old generation.
4. Once activation completes, a later writer re-resolves and writes only to the new generation.
5. Rollback fails closed if the previous generation's overlay is absent or bound to different digests.
6. Crash/fault injection before pointer rename leaves the old generation active.
7. Crash/fault injection after pointer rename leaves the new generation active with its already-created overlay.
8. Stale store handles cannot append after a generation switch.
9. Corrupt lock/pointer/descriptor/overlay state produces diagnostics and never selects canonical history silently.
10. Existing baseline record-array digests remain unchanged through all overlay writes and exports.

Robolectric tests may validate ordering and fault injection but do not prove cross-process file-lock behavior. At least one Android instrumentation test using two real processes is required before any startup wiring.

## Explicit non-actions

This design does not authorize startup integration, activation, rollback, migration, changing canonical stores, touching user history, installing an APK, or changing a device. Those remain separate milestones requiring their own tests and authorization.

## Open questions before implementation

- Confirm the app's minimum supported Android API and whether `FileChannel.lock()` is available and reliable across its supported Android versions.
- Confirm all generation-sensitive entry points and eliminate raw store-handle escape paths.
- Decide whether generation descriptors should explicitly include overlay schema version and overlay database filename before activation can publish a generation.
- Specify bounded lock acquisition / timeout behavior for UI responsiveness without ever bypassing the lock.
