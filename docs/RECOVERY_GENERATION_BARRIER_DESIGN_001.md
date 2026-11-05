# Recovery Generation Barrier Design — 001

Status: barrier, scoped overlay facade, barrier-protected activation/rollback/selection, and generation-aware canonical writer routing implemented on the PR branch. Android unit tests and instrumentation-APK compilation both passed on commit `f408802ce3aea971edcd028ea2fe8de2ac358ea7` in CI run [42099356290](https://github.com/mcoke9-lgtm/Yggdrasil-Lean/actions/runs/42099356290). The cross-process instrumentation test has not been executed on a device. No app installation or device-level verification.

## Purpose

Prevent conversation and subject writers in separate Android processes from observing or writing against different recovery generations during activation, rollback, restart, or recovery. Preserve immutable staged baseline databases and their exact record-array SHA-256 digests.

## Current verified facts

- `BuilderRecoveryActivation` publishes a generation descriptor and switches one active pointer using a same-directory atomic rename with file and directory sync.
- Its `activate`, `getActive`, and `rollback` entry points now acquire the cross-process generation barrier before touching the pointer or generation databases. Their prior method-level `synchronized` modifiers were removed to avoid lock-order inversion with callers that already hold the barrier.
- `BuilderRecoveryStartupSelector.resolve` now holds the same barrier across the manifest-existence check and active-pair validation, preventing a stale canonical selection racing with pointer publication.
- Conversation writes are owned by `BuilderRuntimeService` in the `:builder_runtime` process; it now uses `BuilderRecoveryConversationJournal`, which resolves the active generation under the barrier and routes writes/reads/exports to the matching overlay. Before any generation is active, it preserves legacy behavior against `builder-conversations.db`.
- Subject-event writes initiated by `BuilderSubjectThreadRecorder` now follow the same guarded active-generation decision; before activation they use the existing subject index, and afterward they append to the generation overlay.
- `BuilderRecoveryOverlayStore` uses one SQLite database for both post-baseline streams and binds itself to generation ID plus both baseline digests.
- These are source-level writer-routing changes only. They have not been installed or exercised on a physical device; no automatic startup activation, migration, or user-history mutation is performed.

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

The two identified canonical writer paths now route through the barrier and scoped overlay facade. This still is not a universal enforcement boundary: package-local code can call the package-private overlay store directly, and other code can instantiate the public legacy `BuilderConversationStore` or `BuilderSubjectThreadIndex` classes directly. A complete audit of all 41 Java source files under `app/src/main/java` found no additional direct constructions outside the builder package. The legacy `BuilderConversationStore` and `BuilderSubjectThreadIndex` classes have now been narrowed from public to package-private, preventing callers in other app packages from bypassing the adapters. Package-local code can still instantiate them directly, so a final in-package write-path audit/API consolidation remains. The selector is a guarded resolver, not automatic startup activation. Cross-process runtime semantics remain unverified until the instrumentation test is run on a device.

## Access-facade checkpoint

Added `BuilderRecoveryOverlayAccess` and focused tests. The facade resolves the active generation only while holding the cross-process lock; validates the active overlay's generation and both baseline digests; scopes store use to a callback; exposes no `SQLiteDatabase` handle; invalidates retained sessions after callback completion; and closes the helper before lock release. Activation, active-generation validation, rollback, and selector resolution are now also fenced. The remaining known bypass is direct package-local store access. The full main-source audit identified legitimate internal use in `BuilderRecoveryActivation`/`BuilderRecoveryStager` for generation validation and staging, and legacy fallback in the generation-aware journal/subject recorder. The two legacy store classes are now package-private. Further consolidation is needed if we want to prevent arbitrary package peers from invoking their write APIs.

## Real-device cross-process test

Added a debug-only service in `app/src/debug` running under `:recovery_lock_probe` and an Android instrumentation test in `app/src/androidTest`. The test acquires the barrier in the instrumentation/target process, asks the separate service process to acquire the same lock, verifies that the second process remains blocked, releases the first, and verifies that the second then acquires and releases it. CI is configured to compile the instrumentation APK. The unit-test phase passed on run [42098162418](https://github.com/mcoke9-lgtm/Yggdrasil-Lean/actions/runs/42098162418), but that run failed at instrumentation compilation because `android.test.InstrumentationTestCase` is not present in this compile environment. The harness now uses `AndroidJUnit4` and `InstrumentationRegistry`, with AndroidX runner/core/ext-junit dependencies and `AndroidJUnitRunner`. Corrected validation on the current branch head passed: Android unit tests and instrumentation APK compilation in [run 42099356290](https://github.com/mcoke9-lgtm/Yggdrasil-Lean/actions/runs/42099356290). This proves compilation and host-side tests only; the instrumentation test itself has not been executed on a physical device or emulator. The test is not executed by the hosted unit-test runner. It must be run on a physical Android device (Galaxy S21 is the first planned host) or a suitable emulator before accepting cross-process runtime behavior. The debug probe is excluded from release source sets.

## Canonical writer routing and call-site audit checkpoint

Added `BuilderRecoveryConversationJournal`. It holds the generation barrier while deciding whether to use the legacy canonical conversation database or the selected generation's overlay; the selected overlay path is used for append, read, thread listing, and export. `BuilderRuntimeService` now depends on the `BuilderConversationJournal` interface and instantiates this adapter. `BuilderSubjectThreadRecorder` now performs the same barrier-protected routing for atomic event batches. Added `BuilderRecoveryConversationJournalTest` for pre-activation legacy behavior, post-activation conversation routing, and subject recorder routing. A static call-site audit covered all 41 Java files in `app/src/main/java`; it found no other direct legacy-store construction outside the builder package. `BuilderConversationStore` and `BuilderSubjectThreadIndex` are now package-private. These tests passed in CI run [42099356290](https://github.com/mcoke9-lgtm/Yggdrasil-Lean/actions/runs/42099356290), alongside compilation of the cross-process instrumentation APK.

This is a source-code integration of the canonical writers, not a deployed app change. The selector remains a guarded resolver, not automatic startup activation. Existing users' canonical databases are not migrated, rewritten, or deleted by this change.

## Explicit non-actions

No app installation, APK download, Galaxy S21 modification, automatic startup activation, recovery activation/rollback execution against user data, canonical-history migration, signing-key/configuration change, or deployment has occurred. Those remain separate milestones requiring their own tests and authorization.

## Open questions before implementation

- Verify `FileChannel.lock()` semantics on a physical Android device and the actual app-private filesystem; `minSdk 26` satisfies the API availability requirement but not this runtime acceptance gate.
- Confirm all generation-sensitive entry points and eliminate raw store-handle escape paths.
- Decide whether generation descriptors should explicitly include overlay schema version and overlay database filename before activation can publish a generation.
- Specify bounded lock acquisition / timeout behavior for UI responsiveness without ever bypassing the lock.
