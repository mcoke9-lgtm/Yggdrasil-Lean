# Review History Adapter Contract 001

**Status:** Proposed interface contract; not implemented or adopted  
**Parent documents:** [Durable Review History Specification 001](DURABLE_REVIEW_HISTORY_SPEC_001.md), [Persisted Review Record Schema 001](PERSISTED_REVIEW_RECORD_SCHEMA_001.md)  
**Scope:** Backend-neutral operations and outcome semantics for durable transition-review history

## 1. Purpose and boundary

Define the observable contract between Yggdrasil-Lean and any future persistence adapter. The interface must make durable outcomes, missing history, corruption, concurrency conflicts, and uncertain commits explicit.

This specification does not select a database, file or network protocol, serialization format, identifier generator, timestamp authority, digest algorithm, or deployment model. It does not claim that an adapter currently exists.

The current Lean history structures are immutable in-memory values. Their append-order theorems do not establish durable commit, concurrent-writer behavior, or restart recovery.

Governing invariant:

> A transformation may change a structure’s configuration, but must preserve identity and provenance of parts, record changes to relationships, and disclose unrecoverable information.

Changing conversation location alone is not a project or branch transition. A branch change must be explicitly recorded.

## 2. Logical inputs and shared terms

Adapter operations use logical records from the persisted-record schema, independent of their wire representation.

- **Event ID:** stable identity of one historical event.
- **Idempotency key:** retry identity, scoped according to a future reviewed policy.
- **Payload identity:** equality of all schema-defined logical fields, including explicit unavailable markers and order-sensitive lists. This is a logical comparison, not byte equality; the encoding must not change it.
- **Commit boundary:** backend-specific point at which the adapter can truthfully report a record as durably committed under its documented failure model.
- **Receipt:** evidence identifying the committed event and its assigned history position, plus any backend receipt/reference available. Its guarantees and limitations must be documented.
- **Cursor:** opaque continuation token for a read; representation and lifetime are adapter-defined and documented.
- **Assessment provenance:** whether a returned assessment is the exact stored historical assessment, a reconstruction, or a new reassessment.

Fields that cannot be supplied or recovered must be explicitly represented as unavailable, with reason where known. The adapter must not fabricate defaults that alter meaning.

## 3. Required operations

The following are logical operations, not proposed Lean signatures. A later API may combine or split them only if it preserves the outcome distinctions.

### 3.1 Append

**Input:** complete logical event payload and idempotency key; the adapter may validate event identity and expected history position.

**Outcomes:**

- **Committed(receipt):** the event crossed the documented commit boundary and is visible as a complete record.
- **AlreadyCommitted(receipt):** the same idempotency key and equivalent logical payload already identify a committed event; no duplicate was created. If the same event ID is found with equivalent payload under a different key, the adapter must also avoid duplication and return a documented existing-event outcome or an explicit conflict; it must never silently create a second copy.
- **Conflict(reason):** the key is associated with a different payload, an event ID conflicts with existing history, or a concurrency condition failed.
- **AmbiguousIdentity:** more than one stored record matches the retry identity and equivalent logical payload, but the matching records identify different event IDs. The adapter must not select a canonical event based on result/list order. This is distinct from a caller's key/payload conflict and from an ordinary unavailable read. The adapter should surface diagnostic evidence and require history repair or explicit reconciliation before treating the retry as resolved.
- **Unavailable(retryability, reason):** a service, permission, capacity, or connectivity failure prevents establishing the requested outcome. Retryability must be stated as known, not guessed.
- **Corrupt(details):** relevant stored data or required references failed validation, so the operation cannot safely proceed.
- **CommitUnknown(reconciliation handle):** the adapter cannot establish whether the write crossed the commit boundary. This is neither success nor definite failure.

A successful in-memory append, serialization, queue submission, or network request alone is not Committed.

### 3.2 Read by event identity

**Input:** event ID, optionally with expected project/branch context.

**Outcomes:**

- **Found(record, provenance):** one validated record was retrieved; provenance identifies it as stored historical data.
- **NotFound(scopeEvidence):** the adapter establishes absence across the complete declared lookup scope. The evidence must identify the scope and support its completeness; an empty partial result is insufficient.
- **AmbiguousIdentity(diagnostic, candidateEventIds?, lookupEvidence):** the query resolves to multiple competing records with different event IDs. Do not choose a result by list order. Candidate IDs are only the observed candidates and must not be represented as exhaustive unless the lookup evidence establishes completeness for the relevant scope.
- **UnsupportedSchema(version):** the record exists but cannot be interpreted safely by this implementation.
- **Corrupt(details):** bytes, integrity metadata, lineage, or required references fail validation.
- **Unavailable(retryability, reason):** the adapter cannot establish a reliable read result, including when lookup scope or completeness cannot be established and no more specific outcome applies.

A read failure or inaccessible backend must never be reported as NotFound. If multiple physical matches resolve to the same event ID, that alone does not establish competing event identity; uniqueness and payload consistency remain separate checks. Unsupported schema or corruption must not be converted into confirmed absence.

### 3.3 Read ordered history

**Input:** project ID, branch ID, optional cursor, and documented page limit.

**Outcomes:**

- **Page(records, next_cursor, completeness):** validated records in stable history order. Completeness states whether the segment is complete and whether a known gap exists.
- **Empty:** a complete query confirms no matching history in scope.
- **GapOrConflict(details):** a missing sequence, conflicting identity, broken lineage, or inconsistent ordering was detected.
- **UnsupportedSchema(version), Corrupt(details), or Unavailable(retryability, reason)** as applicable.

The adapter must define ordering and cursor behavior across concurrent appends. Wall-clock timestamps alone are not a sufficient order. A multi-page read must either bind its cursor to a consistent history snapshot or explicitly report that the pages are not a consistent snapshot; it must not imply complete snapshot recovery when it cannot provide it. If order cannot be established, disclose that limitation rather than invent one.

### 3.4 Reconcile uncertain commit

**Input:** idempotency key and/or event ID returned by CommitUnknown.

**Outcomes:**

- **Committed(receipt):** evidence establishes that the original append committed.
- **NotCommitted:** evidence establishes that it did not commit and a retry is safe under the documented contract.
- **StillUnknown:** available evidence cannot determine the outcome.
- **Conflict, Corrupt, or Unavailable** when those conditions prevent reconciliation.

StillUnknown must remain visible. The caller must not blindly create a second event with a new key. A retry with the original key is permitted only according to the documented idempotency guarantee.

### 3.5 Verify record and lineage

**Input:** an event ID or record plus the required referenced objects.

**Outcomes:**

- **Valid:** the checks specified by the chosen integrity design succeeded.
- **Invalid(details):** integrity or structural checks failed.
- **Incomplete(details):** required references, history segments, or evidence are unavailable.
- **UnsupportedSchema(version) or Unavailable(retryability, reason)** as applicable.

Before canonical encoding and integrity rules are adopted, the implementation must not claim cryptographic digest verification. The result must state which checks were performed and their scope.

### 3.6 Recover or reassess

Recovery must expose separate paths:

- **Retrieve original:** return the stored assessment as originally recorded, preserving event identity, rule snapshot, decision order, and provenance.
- **Reconstruct:** derive a result from recovered inputs when the original assessment is absent or unavailable. Mark it reconstructed, identify method/version, and never present it as the original.
- **Reassess:** evaluate the transition under a specified rule snapshot and append a new event linked to the original. Never overwrite or relabel the original.

Each path must return explicit findings for missing fields, unsupported versions, broken references, and incomplete recovery. Recalculation must not silently substitute for retrieval.

## 4. Cross-operation guarantees

1. **Append-only history:** committed events are immutable. Correction, migration, and reassessment are represented by new linked events or an explicitly reviewed migration record.
2. **Atomic visibility:** readers observe a complete committed event or no committed event; incomplete writes are not valid history.
3. **Idempotency:** same key plus equivalent payload returns the existing committed result; same key plus different payload is a conflict.
4. **Identity scope:** event IDs, idempotency keys, project IDs, and branch IDs have documented scopes. No global uniqueness is assumed without an adopted mechanism.
5. **Concurrency:** simultaneous writers either obtain a deterministic valid order or receive an explicit conflict/retry outcome. The adapter documents its serialization point and conflict policy.
6. **No false success:** Committed is returned only after the declared commit boundary is confirmed. If the backend cannot establish the outcome, return CommitUnknown.
7. **No false absence:** permissions, network failures, unsupported schemas, and corruption must not be disguised as NotFound or empty history.
8. **Stable fidelity:** state lists, routes, decision lists, and parent links retain defined order and values. A field may be declared order-insensitive only through an explicit reviewed change.
9. **Schema evolution:** unknown versions are explicit outcomes. Migration preserves the source record and identifies migration method/version; it must not silently rewrite the historical assessment.
10. **Cancellation and timeout:** cancellation before confirmed commit may still leave an uncertain outcome. The caller receives a result preserving that uncertainty and a reconciliation path.
11. **Security and retention:** access denial, retention/deletion policy, backup, and restore guarantees must be documented by the chosen adapter. This contract does not define who may read or delete records.
12. **Observability:** failures and reconciliation decisions must be recordable without logging secrets or claiming more evidence than the adapter has.

## 5. Required outcome model

| Condition | Required representation | Must not be collapsed into |
|---|---|---|
| Confirmed durable write | Committed(receipt) | queued / serialized |
| Same event already committed | AlreadyCommitted(receipt) | duplicate write |
| Key, ID, or ordering conflict | Conflict | unavailable |
| Multiple competing records for one queried identity | AmbiguousIdentity | arbitrary Found / list-order winner |
| Backend cannot serve request or lookup completeness is unknown | Unavailable | not found |
| Validation/integrity failure | Corrupt or Invalid | empty history |
| Commit outcome uncertain | CommitUnknown / StillUnknown | committed or not committed |
| Confirmed absence | NotFound / NotCommitted | read error |
| Version cannot be interpreted | UnsupportedSchema | corrupt, unless corruption is independently established |
| History incomplete or lineage broken | GapOrConflict / Incomplete | complete recovery |

The interface may refine these categories but must not remove their distinctions.

## 6. Six-Part Integrity Check and adapter acceptance

Exactly six acceptance checks are required. All are currently **proposed**; none is represented as executed or passed by this document.

1. **Identity and provenance:** append and retrieve events while preserving event/project/branch identity, transition endpoints, parent lineage, and explicit fork semantics.
2. **Commit, idempotency, and uncertainty:** test confirmed commit, same-key/same-payload retry, same-key/different-payload conflict, interrupted write, timeout, and commit reconciliation.
3. **Ordering and concurrency:** test sequential and simultaneous writers, stable per-branch ordering, cursor paging, and explicit conflict or gap outcomes.
4. **Read fidelity and recovery:** restart the process and retrieve exact state lists, rule snapshots, decision values/order, and original assessment provenance.
5. **Integrity, schema, and failure visibility:** test altered/truncated records, missing references, unknown schema versions, access denial, unavailable storage, and capacity failure; confirm each is reported without false success or false absence.
6. **Original versus reconstruction/reassessment:** in a controlled test, make an original assessment unavailable; verify reconstruction is labelled distinctly, and reassessment appends a linked event without changing the original.

A check is **passed** only with recorded evidence from the actual adapter and target storage. Lean proofs over pure values do not satisfy restart, durability, or integration tests.

## 7. Six-Part review of this contract

| # | Role | Status | Evidence / remaining gap |
|---|---|---|---|
| 1 | Identity and provenance | Proposed | Event IDs and lineage specified logically; no implementation |
| 2 | Commit, idempotency, uncertainty | Proposed | Outcomes defined; commit boundary depends on selected backend |
| 3 | Ordering and concurrency | Proposed | Required behavior stated; serialization/conflict policy remains open |
| 4 | Read fidelity and recovery | Proposed | Original, reconstruction, and reassessment paths separated |
| 5 | Integrity, schema, failure visibility | Proposed | Result distinctions defined; canonical encoding and digest remain open |
| 6 | Original/reconstruction/reassessment separation | Proposed | Event relationships specified; integration behavior untested |

**Count check:** six checks, mapped one-to-one to six adapter responsibilities. The contract does not claim any check is implemented, tested, or adopted.

## 8. Semantic clarifications required of every implementation

These rules narrow interface ambiguity without selecting a backend or encoding.

1. **Logical payload equality:** compare all schema-defined fields. Preserve the exact order of ordered lists and distinguish absent, unavailable, and empty values. Canonical bytes are not required for this logical comparison, but a later encoding must preserve the same distinctions.
2. **Idempotency and event identity:** the same idempotency key with a different logical payload is always a conflict. The same key and same payload must not create a duplicate. Reuse of an event ID must likewise never create a second event: equivalent payload resolves to the existing event under a documented result; a different payload is a conflict.
3. **Per-branch order:** every committed event has one unambiguous position in its project/branch history. Position assignment and append visibility must be atomic with respect to competing writers. The implementation may serialize writers or reject stale expected positions, but must disclose which policy it uses.
4. **Read completeness:** an empty result is valid only when the adapter can establish that the requested scope was queried completely. Pagination must preserve order. For multi-page reads, either a cursor binds a consistent snapshot or the API explicitly marks the read as non-snapshot and cannot label it a complete snapshot recovery.
5. **Safe retry after uncertainty:** NotCommitted is allowed only when the adapter has authoritative evidence that the original event did not commit and retrying with the same key cannot create a duplicate. Otherwise the result remains StillUnknown.
6. **Receipt meaning:** a receipt identifies the committed event and its assigned history position. The adapter documents the failure scenarios covered by its commit guarantee, including any limits around process crash, host loss, replication, or restore. No universal durability guarantee is inferred from the word “committed.”
7. **Assessment provenance:** each read result identifies whether it is the exact stored assessment, a reconstruction from stored inputs, or a new reassessment. If the original is unavailable, return that fact separately from any reconstructed result.
8. **Migration fidelity:** a migration must retain the original record or an immutable reference to it, record migration method/version, and disclose any field that cannot be preserved. A migrated representation must not be passed off as the byte-identical original.
9. **Fork-event placement:** a `branch_fork` event must identify its source and destination branches and specify which branch history (or histories) contain the event, how sequence positions are assigned, and how the destination branch's initial history links to the fork. These rules must be explicit before implementation; a conversation change alone never creates a fork event.

## 9. Decisions deliberately left open

- Backend, hosting boundary, and durability/replication guarantees.
- Canonical encoding, normalization, digest algorithm, and threat model.
- Event ID and idempotency-key generation and scope.
- Sequence assignment and concurrent-writer policy.
- Cursor stability and snapshot semantics during concurrent writes.
- Snapshot embedding versus immutable references.
- Schema migration, retention, backup, access control, and deletion policy.
- Which failures are safely retryable for each backend.

These choices require evidence and review before implementation. Do not choose a backend merely because its interface makes the proposed contract easier to satisfy.

## 10. Next step

Review this interface contract against the current Lean model and persisted-record schema. Resolve semantic ambiguities before writing an adapter API or selecting storage. Then implement one narrow adapter and run the six acceptance checks against its actual target storage, including a fresh-process restart.


## 11. Identity ambiguity and confirmed-absence boundary

When more than one stored record matches a retry identity and equivalent logical payload but the records have different event IDs, return `AmbiguousIdentity`. Do not select a canonical event by list order. Append and reconciliation must preserve the same distinction if lookup by key or event ID discovers competing candidates.

**Identity-read refinement proposed by the outcome-type review:** add `AmbiguousIdentity(diagnostic, candidateEventIds?, lookupEvidence)` to the read-by-event-identity outcomes when the queried identity resolves to multiple competing records with different event IDs. This is a primary outcome, not merely a diagnostic attached to `Found` or `Unavailable`. `Found` must not conceal competing matches; `Unavailable` is for a result that could not reliably be established. The candidate list is not exhaustive unless lookup evidence establishes completeness for the relevant scope. Multiple physical matches with the same event ID do not alone prove competing identity.

**Confirmed absence:** `NotFound(scopeEvidence)` is permitted only when the adapter establishes absence across the complete declared identity lookup scope. Incomplete indexes, partial pages, permission failures, unavailable storage, unsupported schema, and corruption cannot be converted into `NotFound`. If scope or completeness cannot be established, return `Unavailable` or a separately defined incomplete/unknown outcome; do not guess absence.

This is a proposed contract refinement, not adopted API semantics. It requires review against the persisted-record schema and final API outcome inventory before Lean declarations change. No current adapter or storage-backed behavior is implied.
## 12. Incomplete identity lookup versus unavailable operation — clarification proposed

**Status: proposed contract clarification; not adopted and not an implementation claim.**

The adapter must distinguish two facts when the evidence supports them:

- **Known incomplete lookup:** the adapter has positive evidence that the required declared lookup scope was not fully searched (for example, a partial page or incomplete index result). It must not return `NotFound`.
- **Unavailable/unknown lookup:** the operation failed before it could establish completeness or a reliable identity result (for example, storage, connectivity, or permission failure). The adapter must not pretend it knows the lookup was incomplete in a specific way unless it has that evidence.

Candidate disposition matrix:

| Evidence observed | Required behavior |
|---|---|
| Complete declared scope searched; no match | Candidate `NotFound(scopeEvidence)` |
| Explicitly partial coverage | Preserve incomplete status/evidence; no `NotFound` |
| Cannot determine whether scope was complete | Return unresolved/unavailable with completeness marked unknown |
| Multiple competing event IDs observed | Preserve `AmbiguousIdentity`; state whether candidate list is exhaustive |
| One match observed, but lookup completeness unknown | Do not claim unique `Found` across the requested scope unless the contract defines `Found` as a local match and separately exposes incomplete coverage |

The contract should ultimately choose between a distinct incomplete-read outcome and mandatory completeness metadata. A single generic `Unavailable` is acceptable only if callers cannot mistake it for confirmed absence and can reliably inspect the incomplete/unknown distinction. This decision remains open pending API review and IT-6 acceptance criteria. No Lean constructor shape is selected here.

## 13. Caller obligations and incomplete-read disposition — recommendation

**Proposed, not adopted:** callers should be forced to distinguish known incomplete lookup from unavailable/unknown lookup. A free-form diagnostic alone is insufficient.

A known incomplete result must carry lookup-scope/completeness evidence and may preserve observed matching records as partial evidence. It must not imply absence or uniqueness across the requested scope. If multiple competing event IDs have been observed, preserve AmbiguousIdentity and mark the candidate set non-exhaustive unless completeness is established. If the operation fails before completeness can be assessed, return unavailable/unknown rather than asserting known incompleteness.

The final API may encode this as a distinct Incomplete result or an equivalent mandatory tagged field that callers must exhaustively handle. The contract should reject an optional-only flag that callers can omit or ignore. Exact constructor names and fields remain open; no Lean or backend behavior is implied.
