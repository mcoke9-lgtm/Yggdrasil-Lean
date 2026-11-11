# YIM-001 Create-to-Provenance Integration Design

**Version:** 0.1  
**Status:** Proposed design; not adopted  
**Date:** 2026-11-11  
**Scope:** Analyze a linked provenance event for Core Memory `create` only. No source, test, or adopted-specification changes are authorized by this design record.

## 1. Existing verified boundary

- The CLI `create` command loads and validates the complete Core Memory snapshot, creates a record/version in memory, then atomically replaces the Core Memory snapshot.
- The separate provenance ledger has its own complete-history validation, append-only event sequence, unique event IDs, sequence numbers, and SHA-256 hash chain.
- Existing event type `record.created` is available. Event rows can reference `record_id` and `version_id`, and carry JSON `details`.
- The current `reconcile-create` adapter reads `details.record_digest` and compares it against caller-supplied expected digest.
- There is no cross-file transaction. Atomic replacement of either file does not make the pair atomic.
- Current `create` has no ledger path or actor parameter. Actor IDs in provenance are claims, not authenticated identities.

## 2. Required event payload for a successful create

A `record.created` event should reference the exact Core Memory identifiers returned by the create operation:

- `record_id`: new record identifier.
- `version_id`: initial version identifier.
- `details.record_digest`: SHA-256 digest of a documented canonical representation of the created initial version envelope.
- `details.operation_id`: stable operation/idempotency identifier, unique for the create attempt and reused for retries.
- `details.schema`: a versioned event-payload schema identifier.

The digest input should bind at least the record ID, version ID, empty parent list, content, and metadata. The canonical serialization and digest domain must be specified once and shared by event creation and reconciliation. Do not digest only the user content: that would not bind the event to the actual record/version identities.

Keep event ID, record ID, version ID, and operation ID as distinct identifiers. Do not overload one as another.

## 3. Cross-file failure boundary

A naïve sequence cannot guarantee a coupled create-plus-event operation:

- **Memory first, event second:** a crash or ledger-write failure after the memory snapshot commits leaves a created record without its event.
- **Event first, memory second:** a crash or memory-write failure leaves an event claiming a record that was never committed.
- Atomic replacement protects each individual file only; it does not remove either gap.

Therefore the implementation must not report an ordinary success that implies both files were committed unless it has verified both. Nor should it silently delete or rewrite an append-only event to hide a partial outcome.

## 4. Recommended design direction: explicit intent and completion events

Proposed two-event protocol, subject to specification adoption:

1. Validate the complete existing Core Memory snapshot and complete existing provenance ledger before any authoritative mutation.
2. Construct the new record/version candidate and stable operation ID without mutating either persisted file.
3. Append a `record.create_intent` event containing operation ID, candidate record/version IDs, and the canonical candidate digest. Persist and verify the ledger append.
4. Persist the Core Memory snapshot containing that exact candidate.
5. Append a `record.created` completion event referencing the intent event ID and repeating the operation ID, record/version IDs, and digest.
6. Report success only after both the Core Memory snapshot and completion event have been read back and verified.

The intent event is a durable statement that the operation was requested, not proof that the record exists. The completion event is the provenance claim that the memory snapshot was committed. Reconciliation must distinguish intent-only, completion-with-matching-record, missing completion, and contradictory evidence.

This protocol makes partial progress observable; it does **not** create a cross-file transaction. A crash after step 3 or 4 leaves an explicit unresolved operation. Resolving such a state needs a separately specified retry/recovery rule; ordinary create must not silently repair it.

## 5. Retry and recovery requirements

- A retry must reuse the original operation ID and candidate identities/digest when continuing the same operation.
- The ledger must reject duplicate operation IDs for a given create-intent protocol, or provide a deterministic lookup that makes duplicate intents unambiguous.
- If an intent exists but the memory record does not, the system must not silently claim success.
- If the memory record exists and matches the intent digest but completion is absent, the result is an uncertain/incomplete commit state until an adopted recovery/retry procedure resolves it.
- If the record exists but differs from the intent digest, report conflict/uncertainty; never overwrite it to force agreement.
- Recovery must preserve original events and record snapshots, be separately authorized, and append its own evidence. No recovery implementation is proposed here.

## 6. CLI surface proposal

A future explicit opt-in could add a ledger path and actor claim to create, for example `create STORE CONTENT --ledger LEDGER --actor ACTOR_ID`. This avoids silently changing existing create semantics while the coupled protocol is being adopted.

The actor value must be presented as a caller-supplied claim, not authentication. A default actor label should not be invented or described as verified identity.

The legacy create path without a ledger should remain unchanged until the operation-to-history specification is explicitly amended to make a ledger mandatory or to define this opt-in mode.

## 7. Tests required before implementation is considered conformant

- Complete Core Memory and ledger validation occurs before the first append or snapshot replacement.
- Invalid/unreadable memory or ledger yields `UNCERTAIN` and neither file changes.
- Intent append failure leaves the memory snapshot unchanged.
- Memory snapshot failure after intent preserves the intent and original memory snapshot.
- Completion append failure after memory commit preserves both the committed record and intent; CLI reports an uncertain/incomplete outcome, not ordinary success.
- A successful create has exactly one matching intent and one completion event with identical operation ID, record/version IDs, and digest.
- Retry with the same operation ID does not create a duplicate record, duplicate intent, or ambiguous completion.
- Reconciliation distinguishes intent-only from completed creation.
- Byte-for-byte assertions cover both files at every rejected/pre-commit path; post-commit uncertainty tests assert the actual partial state.
- Tests explicitly avoid implying cross-file atomicity.

## 8. Decisions needed before implementation

1. Adopt the intent/completion event protocol, or choose a different explicit partial-commit model.
2. Decide whether ledger integration is opt-in or mandatory for create. This changes the current operation-to-history dependency recorded in the adopted write-gating specification.
3. Adopt the canonical initial-version digest representation and payload schema.
4. Define operation-ID generation, persistence, uniqueness, and retry lookup.
5. Define the actor claim interface and wording.
6. Define the boundary between ordinary retry and separately authorized recovery after an uncertain partial commit.

## 9. Current disposition

**Proposed only.** No source code, tests, adopted specification, MRP-001 Revision 4, or existing event history was changed by this design record. Direct-conflict versus malformed-history precedence remains unresolved.
