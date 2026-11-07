# Startup Recovery Integration Readiness — 001

**Status:** Investigation / design gate  
**Inspected revision:** `ce7a3115eef2a98396084f311227966987bb0a81`  
**Related PR:** [#33 — Add append-only subject-to-thread continuity index](https://github.com/mcoke9-lgtm/Yggdrasil-Lean/pull/33)

This document records the integration boundary discovered after the read-only startup selector passed CI. It intentionally makes no application-startup or live-history changes.

## 1. Current ownership and call path

The current Android application has distinct owners for the two stores:

1. **Conversation UI:** `org.yggdrasil.scheduler.BuilderConversationActivity` sends journal operations through `BuilderRuntimeJournalClient`.
2. **Conversation runtime:** `BuilderRuntimeService` is declared with `android:process=":builder_runtime"` in `app/src/main/AndroidManifest.xml`. Its `onCreate()` currently constructs `new BuilderConversationStore(getApplicationContext())`, which selects the default `builder-conversations.db`.
3. **Subject metadata:** `BuilderConversationActivity` calls `BuilderSubjectThreadRecorder.record(...)` directly after resolving a subject. The recorder constructs `new BuilderSubjectThreadIndex(context)`, which selects the default `builder-subject-thread-index.db`.
4. **Recovery selection:** `BuilderRecoveryStartupSelector.resolve(context)` returns a coherent conversation/subject database-name pair, but is not called by either store owner.
5. **Activation validation:** `BuilderRecoveryActivation.getActive(context)` validates the manifest, generation descriptor, both database files, row/event counts, and exact SHA-256 digests of the canonical record arrays.

The runtime service and subject recorder therefore need to agree on the same selected pair in any eventual integration. Updating only the conversation service would leave subject metadata writing to the canonical sidecar.

## 2. Blocking invariant: immutable snapshot versus writable journal

The recovery stager creates generation databases from a validated recovery envelope. The activation descriptor records exact digests for both datasets. `getActive()` recomputes those digests and rejects a mismatch.

The live application stores are writable:
- `BuilderConversationStore.append(...)` appends conversation turns.
- `BuilderSubjectThreadIndex.appendAll(...)` appends subject-routing events.

If the selector's recovery database names are passed directly to those existing writers, the first legitimate append changes the corresponding dataset digest. A later `BuilderRecoveryStartupSelector.resolve()` call then fails validation even though the change was an ordinary append by the application.

**Conclusion:** a staged recovery generation cannot simultaneously be treated as an immutable, exact-digest-verified snapshot and as the mutable live journal, unless the integrity model is changed to account for legitimate appends.

This is a design blocker, not a failure in the currently tested selector. The selector is presently read-only and not wired into startup.

## 3. Additional coordination requirement

The conversation database is owned by the `:builder_runtime` process; subject-index writes currently occur from the UI process. A future activation or generation switch must not allow the two processes to select different generations during a transition.

The existing `synchronized` methods in `BuilderRecoveryActivation` provide in-process coordination only. They do not quiesce writers or establish a cross-process generation-switch barrier.

Before activation can be used with live stores, the design must specify how both writers are stopped or fenced, how both resolve the same generation, and how they recover if either process restarts during a transition.

## 4. Safe design directions to evaluate

No direction is adopted by this document. Evaluate at least:

- **Immutable recovery base + append-only writable overlay:** retain the verified staged databases as immutable baseline data and store post-activation writes separately. Define deterministic reads/exports over baseline plus overlay, and bind the overlay to the baseline generation ID.
- **Verified copy into a new writable pair:** validate the immutable staged pair, copy it into a distinct writable pair, then publish one pair pointer. Define a new integrity strategy for the writable pair; do not continue comparing mutable contents to the original snapshot digest.
- **Append-aware integrity chain:** define a transactionally updated, append-only integrity scheme that distinguishes legitimate appends from edits/deletions. It must work across both Android processes and preserve pair consistency.

A design must not silently weaken exact-record verification or quietly treat any digest mismatch as acceptable.

## 5. Required acceptance gates before startup wiring

1. A legitimate conversation append after recovery selection must not make the next startup reject the active state.
2. A legitimate subject-event append after recovery selection must not make the next startup reject the active state.
3. Both stores must resolve to the same generation identity, including across process restart.
4. Interrupted switching must leave either the complete old pair or the complete new pair selected—never a mixed pair.
5. Corrupt, missing, mismatched, or unrecognized state must fail closed with a diagnosable error.
6. Canonical stores and existing user history must remain untouched until an explicit, separately tested transition is authorized.
7. Tests must cover two-process coordination or a faithful deterministic abstraction of it; Robolectric single-process tests alone do not establish physical Android behavior.

## 6. Current scope and evidence

- **Verified:** selector unit tests pass as part of Android CI at commit `ce7a3115eef2a98396084f311227966987bb0a81`.
- **Verified:** selector itself returns a pair and delegates validation to `BuilderRecoveryActivation.getActive()`.
- **Verified by source inspection:** runtime service and subject recorder currently open their default canonical database names; selector is not wired into either.
- **Inferred directly from implementation:** writing to a selected recovery DB changes the exact data digest checked on a later `getActive()` call.
- **Not yet tested as an end-to-end scenario:** the post-activation append/restart failure. It should become an explicit regression test once the writable-generation design is selected.
- **Not verified:** production Android filesystem behavior, cross-process switch coordination, or Galaxy S21 behavior.

## 7. Explicit non-actions

This investigation does not:
- wire the selector into `MainActivity`, `BuilderConversationActivity`, or `BuilderRuntimeService`;
- activate or roll back a recovery generation;
- open, migrate, replace, rename, or delete live databases;
- install or run an app on a physical device;
- merge PR #33 or deploy anything.

The next implementation task is to select and test the immutable-baseline / writable-live-state boundary before startup integration.
