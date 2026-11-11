# YIM-001 Ordinary Write-Gating Specification

**Specification:** YIM-001-WGS  
**Version:** 0.1  
**Status:** Adopted specification addendum, 2026-11-11  
**Authority:** YIM-001 Decision 6 A+B and adopted S2 scope (decision record `tho-015/decisions/YIM-001-MHCP-001-decision-6-write-gating-scope-v0.2.md`)  
**Compatibility:** Does not amend MRP-001 Revision 4.

## 1. Purpose

This addendum defines the ordinary write-gating boundary for YIM-001. Before an ordinary operation mutates authoritative Core Memory state or appends authoritative provenance history, it must validate the complete authoritative history required for that operation. A malformed or unreadable required history blocks the ordinary operation and is reported as `UNCERTAIN`.

This specification governs the required behavior. It does not claim that the current implementation already conforms.

## 2. General requirements

1. **Complete-history validation:** validate the complete required history, not only entries matching the requested record or event.
2. **Pre-mutation gate:** validation must complete successfully before the first authoritative mutation or append.
3. **Fail closed:** malformed or unreadable required history blocks the ordinary write and yields `UNCERTAIN`.
4. **Read-only access:** inspection, verification, and reconciliation remain read-only and available to report the condition where safe.
5. **Recovery separation:** recovery/repair is not an ordinary write. It requires a separately specified, explicit, evidence-preserving procedure. No ordinary-write path may bypass the gate by being relabeled recovery.
6. **Commit uncertainty:** unknown outcome after a possible commit is distinct from pre-mutation validation failure. Existing identity and retry rules govern it.
7. **Strict integers:** schema fields requiring integers reject booleans and other non-integer representations.
8. **No implied transaction:** this addendum does not require or claim atomic transactions across the memory snapshot and separate provenance ledger.

## 3. Operation-to-history requirements

The authoritative history boundary is determined by the actual authority and data dependencies of each operation. A separate history is not required merely because it exists; it becomes required when the operation relies on it or when the architecture declares it authoritative for that operation.

| Operation | Required complete history before ordinary mutation | Rationale and current boundary |
|---|---|---|
| Initialize a new Core Memory store | No pre-existing store history; destination must not already exist | Initialization creates a new empty authority and refuses overwrite. |
| Core Memory create | Complete destination Core Memory snapshot | The operation loads and validates the existing store before adding a record. The separate provenance ledger is not currently linked or declared a prerequisite for this operation. |
| Core Memory revise | Complete destination Core Memory snapshot | The operation loads and validates the store before adding a version. Separate provenance remains unlinked. |
| Core Memory annotate-conflict | Complete destination Core Memory snapshot | The operation loads and validates the store before adding the directional annotation. Separate provenance remains unlinked. |
| Initialize a new provenance ledger | No pre-existing ledger history; destination must not already exist | Initialization creates a new empty ledger and refuses overwrite. |
| Provenance event-append | Complete destination provenance ledger | The append extends the existing authoritative event sequence and hash chain. |
| Read-only Core Memory show/verify | Complete Core Memory snapshot for the requested view/verification | Read-only; malformed/unreadable snapshot is reported, not repaired. |
| Read-only event-show/event-verify | Complete provenance ledger | Read-only; malformed/unreadable ledger is reported, not repaired. |
| Reconciliation | Complete authoritative history designated by the reconciliation contract for the evidence source | Complete-history validation precedes record-specific outcome calculation. The reconciliation function still accepts caller-supplied evidence and a history-state label. A read-only CLI adapter now validates and projects the complete persisted provenance ledger into that API; the adapter is limited to creation-event evidence and does not establish broader store/ledger integration. |
| Future import that mutates an authoritative store | Complete destination snapshot plus every source history the import contract relies upon; validate any ledger to which the import will append | Import semantics and history dependencies must be specified before implementation. |
| Future export that mutates authoritative state or appends authoritative history | Complete source history relied upon and complete destination history for each authoritative append | A read-only export that only produces a non-authoritative portable copy is not, by itself, an authoritative mutation; any authoritative ledger append is separately gated. |
| Future recovery/repair | Governed by a separate recovery specification, not this ordinary-write path | Must preserve original evidence, require explicit authorization, and record the repair. Until specified, no implementation may silently bypass ordinary gating. |

## 4. Reconciliation precedence boundary

The adopted malformed-history rule is applied before duplicate/ambiguity calculation: any malformed event anywhere in the complete required history yields `UNCERTAIN`, including when matching evidence is duplicated.

The direct conflict-versus-malformed-history precedence remains unresolved by the recovered decisions. Until separately decided, this addendum does not silently reorder that case. The normative reconciliation contract must retain an explicit open issue and must not claim the current frozen precedence fully implements Decision 6.

## 5. Conformance tests

At minimum, tests must demonstrate:

- Each ordinary mutator validates its complete required history before any authoritative write-side effect.
- Malformed and unreadable required histories reject the operation with `UNCERTAIN`.
- An unrelated malformed event in a required complete history still blocks the write.
- Rejected operations leave authoritative inputs unchanged.
- Read-only inspection and verification remain available and do not repair or mutate history.
- Reconciliation returns `UNCERTAIN` for malformed complete history even when duplicate matching evidence exists.
- Strict integer fields reject booleans.
- Unknown post-commit outcomes remain distinguishable from validation rejection.
- Future import/export/recovery operations receive operation-specific tests before being considered conformant.

## 6. Known conformance gaps at adoption

The inspected implementation does not yet conform fully:

- Core Memory mutators validate the memory snapshot but do not validate or append to a separate provenance ledger.
- Provenance is a separate ledger and store mutations are not automatically instrumented.
- Event append validates its complete ledger before append.
- A read-only CLI persisted-ledger-to-creation-reconciliation adapter is implemented and tested. It validates the complete ledger before projecting evidence, including unrelated events; broader store/ledger integration and automatic event instrumentation remain unimplemented.
- The reconciliation function's current precedence can return `AMBIGUOUS` when malformed history and duplicate matching evidence coexist; this conflicts with the adopted malformed-history-over-duplicate rule.
- Recovery/repair has no adopted implementation contract.

These are recorded gaps, not permission to change source or tests under this specification-only update.

## 7. Change control

This addendum adopts the operation-to-history boundary above for the current command surface. New mutation operations, changed authority relationships, and recovery procedures require an explicit specification update and corresponding tests. MRP-001 Revision 4 remains unchanged.


## 8. Opt-in linked Core Memory creation

This section defines the normative design boundary for the opt-in linked-provenance form of Core Memory `create`. It does not make provenance-ledger integration mandatory for the existing unlinked create command.

### 8.1 Applicability and compatibility

1. The existing unlinked `create` path MUST retain its existing behavior and MUST NOT begin requiring a provenance ledger merely by virtue of this section.
2. Linked creation is an explicit opt-in operation and MUST receive an explicit provenance-ledger path and caller-supplied actor ID.
3. Missing required opt-in arguments MUST be rejected before any authoritative mutation.
4. The actor ID is an actor claim. The implementation MUST preserve the supplied value in both linked events and MUST NOT describe it as authenticated identity or infer it from the host environment.
5. The linked path MUST validate the complete existing Core Memory snapshot and the complete existing provenance ledger before its first authoritative mutation. Failure to read or validate either required history MUST yield `UNCERTAIN` and leave both authoritative files unchanged.

### 8.2 Event protocol

A linked create MUST use an intent event followed by a completion event:

1. `record.create_intent` MUST be appended and durably verified before the Core Memory snapshot is changed.
2. The exact candidate record/version described by the intent MUST then be committed to the Core Memory snapshot.
3. `record.created` MUST be appended only after the candidate snapshot has been committed and verified.
4. The completion event MUST reference the intent event ID.
5. Both events MUST carry the same operation ID, actor claim, candidate record ID, version ID, and record digest.
6. The intent is evidence of requested work, not proof that a record exists. The completion event is evidence of a completed linked create only when reconciled against the matching committed record.
7. The command MUST report ordinary success only after read-back verification confirms the committed record and matching completion event.
8. Events MUST be append-only. The implementation MUST NOT delete or rewrite an intent or completion event to conceal a partial outcome.

The intent event payload MUST identify its payload schema version and include the operation ID, actor claim, candidate record ID, candidate version ID, and candidate record digest. The completion payload MUST identify its payload schema version and include the intent event ID plus the same operation ID, actor claim, record ID, version ID, and digest. Exact field placement and event schema identifiers MUST be fixed in the implementation contract before implementation.

### 8.3 Canonical digest

1. The initial-version digest MUST use SHA-256 over a versioned canonical JSON representation of the initial version envelope.
2. The envelope MUST bind the record ID, version ID, empty parent list, content, and metadata.
3. Serialization MUST use UTF-8, deterministic object-key ordering, deterministic separators, and a documented digest-domain prefix.
4. Nondeterministic fields MUST be excluded unless explicitly included in the versioned envelope definition.
5. Event creation and reconciliation MUST use the same canonicalization profile and digest-domain definition.
6. The exact canonicalization profile, byte representation, prefix bytes, and test vectors MUST be specified before implementation. A digest of content alone is nonconformant.

### 8.4 Operation identity and retries

1. Each linked create attempt MUST have a stable operation ID, distinct from event IDs, record IDs, and version IDs.
2. The operation ID MUST be established and durably available for reuse before the first intent append. The CLI/API contract MUST define how it is generated, surfaced, and supplied again after interruption before implementation.
3. A retry of the same operation MUST reuse the same operation ID and exact candidate identities and digest.
4. Before continuing a retry, the implementation MUST locate and reconcile the existing intent and any related completion/record evidence.
5. Reuse of an operation ID with different actor claim, candidate identifiers, or digest MUST stop with a conflict or `UNCERTAIN` outcome; it MUST NOT create a second record or a second unrelated intent.
6. A new operation ID denotes a new create request. The ledger MUST enforce uniqueness or provide deterministic lookup sufficient to reject or reconcile duplicate intents.

### 8.5 Partial outcomes and recovery boundary

1. If a matching intent exists and the candidate record is absent, ordinary retry MAY resume the exact candidate only when the operation ID, actor claim, candidate identifiers, and digest all match and the complete required histories validate.
2. If the candidate record exists and matches the intent but the completion event is absent, the ordinary command MUST report `UNCERTAIN`. It MUST NOT silently append the completion event as an ordinary retry.
3. If a matching completion event and matching committed record both exist, retry MUST return the already-completed result without creating another record or event.
4. Contradictory identifiers, actor claims, digests, duplicate/ambiguous operation evidence, or malformed required history MUST stop processing with `UNCERTAIN` or the applicable conflict outcome. The unresolved direct-conflict versus malformed-history precedence elsewhere in this specification remains unchanged.
5. Recovery from an unresolved partial operation is not an ordinary retry. Recovery MUST be separately specified and explicitly authorized, MUST preserve all original records and events, and MUST append its own evidence. Until a recovery specification is adopted, no automatic repair or completion is permitted.

### 8.6 Atomicity and reporting

The Core Memory snapshot and provenance ledger remain separate persistence authorities. This protocol MUST NOT be described as a cross-file transaction or as providing cross-file atomicity. A crash or write failure may leave an intent-only state or a committed record without completion. Such states MUST remain observable and MUST NOT be reported as ordinary success.

### 8.7 Conformance gate

The linked-create path MUST NOT be implemented as conformant until all of the following are fixed and tested: event payload schemas; canonical digest byte profile and test vectors; operation-ID generation/surfacing/reuse contract; actor-claim argument contract; reconciliation outcomes for all partial states; and failure-injection tests at each persistence boundary.

The linked-create path remains opt-in. This section does not authorize recovery implementation, alter MRP-001 Revision 4, or resolve the direct-conflict versus malformed-history precedence question.
