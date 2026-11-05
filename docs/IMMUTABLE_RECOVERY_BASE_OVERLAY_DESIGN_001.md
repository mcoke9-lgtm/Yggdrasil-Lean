# Immutable Recovery Baseline + Shared Append-Only Overlay — Design 001

**Status:** Proposed implementation design; not wired into application startup  
**Related work:** PR #33 — Add append-only subject-to-thread continuity index  
**Design basis:** `BuilderRecoveryActivation`, `BuilderRecoveryStartupSelector`, `BuilderConversationStore`, and `BuilderSubjectThreadIndex` at `b1fee78bdd70816da915b33f25096acaa7ea2c0e`.

## 1. Decision

Use an **immutable, exact-digest-verified recovery baseline** plus **one generation-bound, append-only SQLite overlay database** containing both post-recovery conversation turns and subject-thread events.

Do not make the staged recovery databases writable. Do not weaken or reinterpret their exact SHA-256 checks.

The shared overlay is one database, not separate conversation and subject overlay files. This is intentional: the runtime service and UI process can independently open the same SQLite file, but they must not independently choose two overlay identities. A single database header binds both event streams to one recovery generation and both baseline digests.

This is a design decision for the isolated overlay component only. It does not authorize startup selection, activation, migration, or live-history changes.

## 2. Storage model

A recovery generation consists of:

- **Conversation baseline:** the staged conversation SQLite database named by the immutable generation descriptor.
- **Subject baseline:** the staged subject-index SQLite database named by the same descriptor.
- **Shared overlay:** a new SQLite database named deterministically from the generation ID, e.g. `builder-recovery-<generation>-overlay.db`.

The overlay database contains:

1. A singleton metadata row:
   - overlay format and schema version;
   - generation ID;
   - conversation baseline SHA-256;
   - subject baseline SHA-256;
   - creation timestamp.
2. An append-only conversation-turn table using the canonical turn fields and constraints.
3. An append-only subject-event table using the canonical subject-event fields and constraints.

The metadata row is immutable after creation. The implementation exposes separate explicit `create(...)` and fail-closed `openExisting(...)` entry points; a missing overlay is never implicitly recreated. Opening an existing overlay with a different generation ID, either different baseline digest, unsupported format, or schema version must fail closed. Never silently rebind or reset an existing overlay. SQLite triggers reject UPDATE and DELETE operations on both event tables and the identity row.

The baseline databases are opened read-only by the overlay reader. The selector/activation layer continues to verify their exact record digests against the generation descriptor; the overlay also recomputes both canonical record-array digests when its SQLite connection is first opened. No write API is exposed for either baseline.

## 3. Append semantics

### Conversation turn

Within one SQLite write transaction:

1. Read the baseline thread in its stored order.
2. Read the overlay thread in overlay insertion order.
3. Reject a turn ID already present in either layer for that thread.
4. Construct the candidate full thread as baseline + overlay + new turn.
5. Run the existing `BuilderConversationProtocol.validateThread` over the complete candidate.
6. Insert only the new turn into the overlay.
7. Commit.

This preserves reply references to earlier baseline or overlay turns and the protocol's strict increasing timestamp requirement. A failed validation or insert commits nothing.

### Subject events

Within one SQLite write transaction:

1. Validate every event before insertion.
2. Reject event IDs already present in either the baseline or overlay.
3. Insert the complete supplied event group into the overlay.
4. Commit all events or none.

Subject events remain independent metadata. An orphan subject association is retained and reported as an orphan during export; it must not cause canonical conversation data to be fabricated or rewritten.

## 4. Read and export semantics

- Thread reads return baseline turns followed by overlay turns, preserving each layer's append order.
- Thread listing is the union of baseline and overlay thread IDs, ordered by most recent activity across both layers.
- Subject-event reads return baseline events followed by overlay events in append order.
- Recovery export materializes the merged logical conversation snapshot and merged subject snapshot, then validates the resulting recovery package using the existing package validator.
- The source baseline databases and overlay database are not mutated by reads or exports.
- The merged export records its generation ID and baseline digests as provenance metadata in the outer recovery envelope (a versioned format change is required; do not silently add fields to version 1).

A merged logical snapshot is a view, not a third writable source of truth.

## 5. Cross-process coordination

The conversation runtime service currently runs in `:builder_runtime`; subject-event recording currently runs in the UI process. Both must open the same generation-specific overlay file and validate the same immutable metadata row.

SQLite's file locking and transactions—not Java `synchronized`—serialize writes across processes. The implementation must not depend on a process-local singleton, in-memory cache, or shared Java lock for correctness. The overlay schema installs database triggers to reject ordinary UPDATE/DELETE statements; this is an application-level append-only guard, not a cryptographic tamper-proof boundary.

The generation ID and both baseline digests are checked on every overlay open. A process with stale or different generation arguments must fail closed before appending. A future startup integration must also establish a process-wide generation-switch barrier; this overlay alone does not make a manifest switch safe while writers are active.

## 6. Identity and ordering rules

- Conversation turn identity: `(thread_id, turn_id)`, matching the canonical conversation store's uniqueness scope.
- Subject event identity: `event_id`, globally unique across baseline and overlay.
- No overlay row may replace, update, or delete a baseline row.
- No duplicate identity may be silently de-duplicated during merge.
- Conversation order is baseline insertion order, then overlay insertion order per thread; the protocol validator remains authoritative for timestamp and reply ordering.
- Subject-event order is baseline insertion order, then overlay insertion order.
- Timestamps are data, not a substitute for append order. Do not globally sort and thereby rewrite the original sequence.

## 7. Failure behavior

Fail closed on:

- missing baseline file;
- baseline descriptor/digest mismatch;
- missing or malformed overlay metadata;
- generation or baseline-digest mismatch;
- unsupported overlay format/schema;
- duplicate IDs across baseline and overlay;
- invalid conversation protocol sequence;
- SQLite corruption or failed transaction.

No automatic fallback to canonical databases, no automatic overlay reset, and no automatic deletion of a failed or stale overlay. Errors must identify the failing layer and reason without exposing message bodies unnecessarily.

## 8. Acceptance tests for the isolated component

1. Create a baseline, open an overlay, append a conversation turn, reopen the overlay, and read the merged thread.
2. Confirm the baseline record-array digest is byte-for-byte unchanged after overlay writes.
3. Resolve/verify the baseline again after the append; it must still pass exact-digest validation.
4. Append subject events and verify the same overlay metadata/generation is used.
5. Reopen the overlay with a different generation ID; reject.
6. Reopen with a different conversation or subject baseline digest; reject.
7. Reject duplicate conversation identity across baseline and overlay.
8. Reject duplicate subject event identity across baseline and overlay.
9. Reject a reply to a nonexistent/future turn and a non-increasing timestamp.
10. Verify a multi-event subject append is all-or-nothing.
11. Verify merged exports preserve baseline-before-overlay order and retain orphan reporting.
12. Exercise two independent database helper instances against the same file; validate transaction serialization and identity checks. Robolectric is a deterministic abstraction, not proof of physical Android behavior.
13. Inject interruption/failure during overlay initialization and append; ensure no partial committed event group and no silent metadata rebind.
14. Verify the overlay cannot modify either baseline database through its exposed API.

## 9. Explicit non-actions and remaining gates

The current isolated implementation provides the generation-bound SQLite overlay API and unit tests, but those tests still require CI execution before the implementation can be called tested or verified. The overlay rows do not yet have a cryptographic append chain or signed provenance; the SQLite triggers are defense against ordinary accidental mutation, not an adversarial integrity guarantee.

This design does **not**:
- wire the selector into `BuilderRuntimeService`, `BuilderConversationActivity`, or application startup;
- activate, roll back, or switch a recovery generation;
- open or modify the user's canonical conversation or subject databases;
- migrate existing history;
- install or run an app on the Galaxy S21;
- claim physical-device or power-loss durability;
- solve the later manifest-switch barrier by itself;
- authorize merge, deployment, signing changes, secrets, paid services, or device changes.

Before startup integration, a separate coordinator must fence both processes during generation selection/switch, guarantee a complete old pair or complete new pair after interruption, and be tested across process restarts. Physical Android filesystem and cross-process behavior remain unverified until exercised on hardware.
