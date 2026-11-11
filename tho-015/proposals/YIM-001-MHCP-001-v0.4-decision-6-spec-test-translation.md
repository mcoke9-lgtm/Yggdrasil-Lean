# YIM-001-MHCP-001 v0.4 — Decision 6 Specification and Test Translation

**Status:** Proposed corrective translation; not normative, not adopted as a specification revision  
**Date:** 2026-11-11  
**Branch:** `tho-015/probe1-ci-bootstrap`  
**Basis:** Adopted Decision 6 A+B and adopted S2 write-gating scope in `YIM-001-MHCP-001-decision-6-write-gating-scope-v0.2.md`

## 1. Purpose and change boundary

Translate the adopted decision-level requirements into a reviewable specification and test plan. This artifact does not modify the normative YIM-001 specification, source code, tests, MRP-001 Revision 4, or production branch.

The central requirement is fail-closed gating for every ordinary state-changing YIM-001 operation: validate all authoritative histories required for that operation before mutation or authoritative append. A malformed or unreadable required history blocks the ordinary operation and is reported as `UNCERTAIN`.

This does not imply cross-file atomicity, automatic provenance instrumentation, multi-writer safety, or a recovery implementation.

## 2. Normative requirements proposed for the next specification revision

**D6-SPEC-01 — Complete-history validation.** Before an operation relies on an authoritative history, the complete required history MUST be loaded and validated, not merely the record-matching subset.

**D6-SPEC-02 — Ordinary write gate.** An ordinary operation that mutates authoritative Core Memory state or appends authoritative provenance history MUST NOT begin its authoritative mutation/append until every history designated as required for that operation has passed validation.

**D6-SPEC-03 — Fail-closed result.** If a required history is malformed, structurally invalid, hash/sequence-invalid, or unreadable, the ordinary operation MUST be rejected. The history condition MUST be reported as `UNCERTAIN`; it MUST NOT be downgraded to `INCOMPLETE`, `AMBIGUOUS`, or a successful outcome.

**D6-SPEC-04 — Reconciliation.** Reconciliation MUST validate complete history before computing record-specific outcomes. Any malformed event in the validated history yields `UNCERTAIN`, including malformed events unrelated to the requested record and malformed history coexisting with duplicate matching evidence.

**D6-SPEC-05 — Read-only availability.** Read-only inspection, verification, and reconciliation MUST remain available where they can safely report the malformed/unreadable condition without mutating authoritative state.

**D6-SPEC-06 — Recovery separation.** Recovery/repair MUST be a separately specified operation class with explicit authorization, preserved original evidence, and an auditable record of the repair. It MUST NOT silently normalize, overwrite, truncate, or discard malformed history. Until that pathway is specified, ordinary-write gating MUST NOT be bypassed by relabeling an ordinary write as recovery.

**D6-SPEC-07 — Commit uncertainty distinction.** A failure or interruption after a mutation may have committed MUST remain distinguishable from pre-mutation history-validation failure. Retry behavior must follow the existing identity/idempotency contract; validation failure must not be represented as an uncertain commit.

**D6-SPEC-08 — Per-operation history map.** The specification MUST identify the authoritative histories required by each operation. It MUST NOT assume a provenance ledger exists, is linked, or is authoritative for a memory-store operation unless the architecture explicitly establishes that relationship.

**D6-SPEC-09 — Strict integer typing.** Integer-valued schema fields MUST reject booleans and non-integer numeric representations where the schema requires an integer.

## 3. Operation-to-history matrix requiring specification resolution

The following is a proposed minimum mapping for review, not a claim that all links exist today.

| Operation | Authoritative mutation/append | Required precondition history to define | Current architectural fact / gap |
|---|---|---|---|
| Core Memory create | Memory snapshot mutation | Complete current Core Memory snapshot; define whether a provenance ledger is required before memory mutation | Current create validates the memory snapshot; provenance is separate and not automatically linked |
| Core Memory revise | Memory snapshot mutation | Complete current Core Memory snapshot; define provenance requirement | Same separation/gap |
| annotate-conflict | Memory snapshot mutation | Complete current Core Memory snapshot; define provenance requirement | Same separation/gap |
| event-append | Provenance ledger append | Complete provenance ledger | Current event CLI already loads and validates the complete ledger before append |
| import that mutates a store | Memory snapshot mutation; possibly event append | Complete destination snapshot and every source/destination history the import contract treats as authoritative | Import/export linkage and exchange contract need explicit definition |
| export that appends an authoritative event | Provenance append (and any other authoritative history append) | Complete ledger receiving the event; define whether source snapshot validation is also required | Current export behavior and provenance linkage need explicit definition |
| recovery/repair | Special mutation/append | Must be separately defined; preserve original and malformed evidence | No adopted recovery pathway yet |

The next normative revision must resolve the open cells above using actual operation semantics. Do not silently require a not-yet-existing ledger for current memory operations. Conversely, do not claim S2 is satisfied merely because the memory snapshot itself validates if the specification has designated another authoritative history as required.

## 4. Test plan

### 4.1 Reconciliation and history validation

1. Valid complete history + unique matching event -> expected record-specific result.
2. Malformed event unrelated to requested record -> `UNCERTAIN`.
3. Duplicate matching evidence + malformed event anywhere in history -> `UNCERTAIN`, not `AMBIGUOUS`.
4. Malformed event plus valid direct record-specific conflict -> **expected precedence remains unresolved** pending a separate user decision; test must be marked pending rather than encode an assumption.
5. Unreadable or invalid-JSON history -> `UNCERTAIN`.
6. Wrong-typed event fields, including boolean where strict integer is required -> rejected as malformed; no uncaught type error should escape the public validation boundary.
7. Missing required history -> preserve the separately specified missing-history outcome; do not conflate absence with malformed/unreadable history.

### 4.2 Ordinary write gating

For each ordinary mutator (create, revise, annotate-conflict, event-append, and each future mutating import/export operation):

1. Valid required histories -> operation may proceed.
2. Malformed required history -> operation rejected before authoritative mutation.
3. Unreadable required history -> operation rejected before authoritative mutation.
4. Unrelated malformed event in a required complete history -> operation rejected before mutation.
5. Verify byte-for-byte or digest-equivalent preservation of all authoritative inputs after rejection.
6. Verify read-only show/verify/reconcile paths remain usable and report the malformed condition.
7. Verify a valid history is not modified by a rejected operation.
8. Verify validation happens before any write-side effect, not merely before the final replace.
9. Verify a failure after the commit point is classified separately from a pre-write validation rejection.

### 4.3 Recovery boundary

Until recovery requirements are separately adopted and specified, tests must ensure ordinary command paths cannot bypass write gating by selecting a recovery-like label or flag. Once a recovery contract is approved, add tests for preserved original evidence, explicit authorization, repair provenance, and post-repair validation.

## 5. Known implementation gaps this plan must not conceal

- The provenance ledger is separate from Core Memory snapshots.
- Store mutations are not automatically instrumented with provenance events.
- The current memory mutators validate the memory store but do not validate a separate provenance ledger.
- Current event append validates its ledger before append.
- No integrated persisted-ledger-to-reconciliation call path was found in the inspected Python tree.
- No recovery/repair workflow has been adopted.
- The current frozen reconciliation precedence is `CONFLICT > AMBIGUOUS > UNCERTAIN > INCOMPLETE > CONSISTENT > NOT_FOUND`, which conflicts with the adopted malformed-history-over-duplicate rule unless complete-history validation occurs before that precedence calculation.
- The direct `CONFLICT` versus malformed-history `UNCERTAIN` precedence remains unresolved.

## 6. Recommended bounded sequence

1. Resolve the per-operation authoritative-history map (Section 3) in the normative design.
2. Resolve the direct `CONFLICT` versus malformed-history `UNCERTAIN` precedence question separately.
3. Update the normative reconciliation and write-gating contract, preserving all adopted decisions.
4. Derive tests from Section 4 and demonstrate failing-before/fixed-after behavior.
5. Only then implement source changes under a separately authorized change scope.

## 7. Status ledger

- Decision 6 A+B: adopted at decision level.
- S2 scope: adopted at decision level.
- This specification/test translation: proposed only.
- Normative specification: unchanged.
- Source: unchanged.
- Tests: unchanged.
- Recovery/repair contract: unresolved.
- Direct conflict versus malformed-history precedence: unresolved.
- Cross-file atomicity: neither required nor claimed.
