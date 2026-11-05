# Recovery Generation Barrier Design — 001

Status: barrier, scoped overlay facade, and barrier-protected activation/rollback/selection implemented; Android unit tests passed on commit `ff777fb3e3d9e1e3f11ce864ac35f83460111ab3`; the first instrumentation APK compile exposed the unavailable legacy `android.test` package; the harness has been migrated to AndroidX and its corrected compile is pending; no live writer or startup-service wiring.

## Purpose

Prevent conversation and subject writers in separate Android processes from observing or writing against different recovery generations during activation, rollback, restart, or recovery. Preserve immutable staged baseline databases and their exact record-array SHA-256 digests.

## Current verified facts

- `BuilderRecoveryActivation` publishes a generation descriptor and switches one active pointer using a same-directory atomic rename with file and directory sync.
- Its `activate`, `getActive`, and `rollback` entry points now acquire the cross-process generation barrier before touching the pointer or generation databases. Their prior method-level `synchronized` modifiers were removed to avoid lock-order inversion with callers that already hold the barrier.
- `BuilderRecoveryStartupSelector.resolve` now holds the same barrier across the manifest-existence check and active-pair validation, preventing a stale canonical selection racing with pointer publication.
- Conversation writes are owned by `BuilderRuntimeService` in the `:builder_runtime` process; subject-event writes are currently initiated from the activity process.
- `BuilderRecoveryOverlayStore` uses one SQLite database for both post-baseline streams and binds itself to generation ID plus both baseline digests.
- The overlay is not wired into application startup or the live stores.

## Proposed primitive: one app-private exclusive OS file lock

Use a permanent lock file in the app-private files directory, for example `builder-recovery-generation.lock`. Implemented in `app/src/main/java/org/yggdrasil/scheduler/builder/BuilderRecoveryGenerationBarrier.java`.

Every operation that resolves or uses the active generation must acquire the same exclusive OS-level file lock and hold it across the complete critical section:
1. Read and validate the active pointer and generation descriptor.
2. Verify the selected baseline and overlay identities.
3. Open or validate the selected stores.
4. Perform the requested read, append, or paired export.
5. Commit any SQLite transaction and close temporary handles.
6. Release the OS lock.

Use `FileChannel.lock()` (exclusive) for the first implementation. Do not rely on shared locks or filesystem-specific shared-lock semantics. SQLite remains responsible for its own database transaction integrity; the file lock coordinates the multi-file generation boundary across Android processes. The Android app declares `minSdk 26`; `java.nio.file` and `FileChannel` are available at that declared API floor. This configuration check does not establish runtime lock behavior on every supported filesystem/device.

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

## Implementation checkpoint

Added `BuilderRecoveryGenerationBarrier` as an isolated primitive. It uses a static process-wide `ReentrantLock`, a permanent app-private lock file, and an exclusive `FileChannel.lock()`. Same-thread nested calls reuse the existing OS lock to avoid overlapping-lock exceptions. Interrupted acquisition restores the interrupt flag and fails closed. Operation exceptions do not skip lock release. The lock file is never deleted or replaced by this class.

The focused Robolectric suite covers lock-file persistence, nested calls, same-process thread serialization, operation-exception cleanup, and rejection of a non-file lock path. These tests passed in CI run [42096632745](https://github.com/mcoke9-lgtm/Yggdrasil-Lean/actions/runs/42096632745). The callback-scoped overlay-access tests passed in CI run [42097440359](https://github.com/mcoke9-lgtm/Yggdrasil-Lean/actions/runs/42097440359). Neither unit-test suite proves cross-process semantics. The primitive has a typed access facade: `BuilderRecoveryOverlayAccess.withActive(...)`. It acquires the barrier before resolving the active pointer, opens and verifies the matching overlay, exposes callback-scoped typed operations, invalidates retained sessions, and closes the SQLite helper before releasing the lock. `BuilderRecoveryOverlayStore` is package-private. The focused Robolectric barrier and access-facade tests passed in CI run [42097440359](https://github.com/mcoke9-lgtm/Yggdrasil-Lean/actions/runs/42097440359).

`BuilderRecoveryActivation.activate`, `getActive`, and `rollback` now acquire the barrier. Activation creates or validates the matching overlay before publishing the pointer. `getActive` refuses a generation whose overlay is missing or bound to different baseline digests. Rollback validates the target overlay before pointer publication. `BuilderRecoveryStartupSelector.resolve` holds the barrier across both the manifest check and generation resolution. Tests cover missing active overlays and rollback targets without overlays. The Android unit-test step passed in CI run [42098162418](https://github.com/mcoke9-lgtm/Yggdrasil-Lean/actions/runs/42098162418).

This is not yet a complete enforcement boundary: package-local code can still call the package-private store directly, and the canonical runtime journal and subject recorder are not yet routed through the barrier. The selector is a guarded resolver, not live startup-service integration. Do not migrate live writers until both are moved together and the cross-process instrumentation test has been run on a device.

## Access-facade checkpoint

Added `BuilderRecoveryOverlayAccess` and focused tests. The facade resolves the active generation only while holding the cross-process lock; validates the active overlay's generation and both baseline digests; scopes store use to a callback; exposes no `SQLiteDatabase` handle; invalidates retained sessions after callback completion; and closes the helper before lock release. Activation, active-generation validation, rollback, and selector resolution are now also fenced. The remaining known bypass is direct package-local store access, plus the two canonical writer paths that have not yet been migrated.

## Real-device cross-process test

Added a debug-only service in `app/src/debug` running under `:recovery_lock_probe` and an Android instrumentation test in `app/src/androidTest`. The test acquires the barrier in the instrumentation/target process, asks the separate service process to acquire the same lock, verifies that the second process remains blocked, releases the first, and verifies that the second then acquires and releases it. CI is configured to compile the instrumentation APK. The unit-test phase passed on run [42098162418](https://github.com/mcoke9-lgtm/Yggdrasil-Lean/actions/runs/42098162418), but that run failed at instrumentation compilation because `android.test.InstrumentationTestCase` is not present in this compile environment. The harness now uses `AndroidJUnit4` and `InstrumentationRegistry`, with AndroidX runner/core/ext-junit dependencies and `AndroidJUnitRunner`. Corrected validation runs [42098458464](https://github.com/mcoke9-lgtm/Yggdrasil-Lean/actions/runs/42098458464) and subsequent branch-head runs are pending. The test is not executed by the hosted unit-test runner. It must be run on a physical Android device (Galaxy S21 is the first planned host) or a suitable emulator before accepting cross-process runtime behavior. The debug probe is excluded from release source sets.

## Explicit non-actions

This design does not authorize startup integration, activation, rollback, migration, changing canonical stores, touching user history, installing an APK, or changing a device. Those remain separate milestones requiring their own tests and authorization.

## Open questions before implementation

- Verify `FileChannel.lock()` semantics on a physical Android device and the actual app-private filesystem; `minSdk 26` satisfies the API availability requirement but not this runtime acceptance gate.
- Confirm all generation-sensitive entry points and eliminate raw store-handle escape paths.
- Decide whether generation descriptors should explicitly include overlay schema version and overlay database filename before activation can publish a generation.
- Specify bounded lock acquisition / timeout behavior for UI responsiveness without ever bypassing the lock.
