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
| Reconciliation | Complete authoritative history designated by the reconciliation contract for the evidence source | Complete-history validation precedes record-specific outcome calculation. The current API accepts caller-supplied evidence and a history-state label; persisted-ledger integration remains an implementation gap. |
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
- No integrated persisted-ledger-to-reconciliation caller was found in the inspected Python tree.
- The reconciliation function's current precedence can return `AMBIGUOUS` when malformed history and duplicate matching evidence coexist; this conflicts with the adopted malformed-history-over-duplicate rule.
- Recovery/repair has no adopted implementation contract.

These are recorded gaps, not permission to change source or tests under this specification-only update.

## 7. Change control

This addendum adopts the operation-to-history boundary above for the current command surface. New mutation operations, changed authority relationships, and recovery procedures require an explicit specification update and corresponding tests. MRP-001 Revision 4 remains unchanged.
