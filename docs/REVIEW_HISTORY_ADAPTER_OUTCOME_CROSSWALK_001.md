# Review History Adapter Outcome Design Crosswalk 001

**Status:** Review note; recommendations only. No interface policy adopted.
**Reviewed sources:** `REVIEW_HISTORY_ADAPTER_OUTCOME_TYPE_DESIGN_001.md`, `REVIEW_HISTORY_ADAPTER_CONTRACT_001.md`, and `PERSISTED_REVIEW_RECORD_SCHEMA_001.md`.
**Purpose:** Check that candidate outcome types preserve the existing contract's distinctions before any Lean type changes.

## Findings

### 1. Commit receipt must not be confused with event payload

The persisted-record schema explicitly separates adapter commit metadata from the event payload. Keep `CommitReceipt`, backend evidence, and reconciliation handles outside the immutable logical event payload unless a separately reviewed schema change establishes otherwise. A receipt's `position` must map to the contract's unique per-branch position, but the base and representation remain unresolved.

### 2. “Not committed” needs stronger evidence than a negative lookup

The contract allows `NotCommitted` only when authoritative evidence establishes non-commit and retrying with the same key cannot create a duplicate. A missing record from an incomplete, stale, inaccessible, or non-snapshot lookup is insufficient. Candidate types should associate the outcome with its evidence scope and preserve `StillUnknown` when the proof obligation is unmet.

### 3. History completeness and consistency are independent dimensions

The candidate `Completeness` and `Consistency` fields are useful and should remain separate. However, `complete` must be defined relative to a declared query scope; it must not imply snapshot consistency. Conversely, a snapshot-bound page may still be incomplete if a known gap or unsupported record exists. Do not collapse gap, conflicting identity, broken lineage, and unavailable lookup into one success-shaped page.

### 4. Provenance must bind to the returned assessment

The schema stores original assessment fields, rule snapshot/routes, and ordered decisions. `storedOriginal`, `reconstructed(method, version)`, and `reassessed(ruleSnapshot, linkedEventId)` must describe the assessment actually returned. A reassessment is a new linked event; a provenance label alone does not persist the event or establish that its rule snapshot is immutable.

### 5. Verification outcome and finding severity need a reviewed relationship

The candidate `VerificationReport` is compatible with the contract if `valid` is limited to named checks completed over the declared scope. Specify how a finding affects the overall outcome: for example, a missing required reference should not coexist with an unqualified `valid`. Do not define digest verification until canonical encoding and the threat model are selected.

### 6. Ambiguous identity must be order-independent and scope-aware

Keep `AmbiguousIdentity` distinct for append and reconciliation when equivalent retry candidates have different event IDs. Candidate IDs are diagnostic evidence, not necessarily a complete set. A partial history cannot prove global uniqueness; the adapter must disclose lookup scope/completeness and must not choose a winner from list order. Ordered-history reads may retain `gapOrConflict` temporarily, but the structured reason remains an explicit design question.

## Recommended next design pass

Before translating the draft into Lean declarations:

1. Define the semantic obligations for `NotCommitted`, `complete`, and `valid` in terms of evidence scope.
2. Specify whether diagnostics are mandatory for each non-success outcome and whether evidence references are stable record IDs, backend references, or opaque tokens.
3. Define the relationship between history-page completeness, known gaps, unsupported schemas, and cursor/snapshot consistency.
4. State how verification findings determine the report outcome without implying that unperformed checks passed.
5. Keep receipt/handle lifetime, event position, access-controlled candidate IDs, and backend guarantees unresolved until the adapter boundary is chosen.

## Six-Part Integrity Check

1. **Count:** Exactly six findings are recorded.
2. **Role mapping:** Findings cover payload/receipt separation; commit uncertainty; history reads; assessment provenance; verification; identity ambiguity.
3. **Completeness:** All six candidate areas are cross-checked; backend durability, atomicity, canonical encoding, and access policy remain out of scope.
4. **Status:** Review recommendations only. No Lean types changed, no adapter implemented, no integration tests executed, no project-wide policy adopted.
5. **Recheck:** The note distinguishes observable type fields from guarantees that require backend evidence.
6. **Persistence/uncertainty:** This note records the review in GitHub if the commit succeeds. It does not resolve the open choices or prove any storage behavior.
