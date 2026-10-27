# Review History Adapter Outcome Type Cross-Review 001

**Status:** Review findings; recommendations only. No interface policy adopted.
**Reviewed records:**
- `REVIEW_HISTORY_ADAPTER_OUTCOME_TYPE_DESIGN_001.md`
- `REVIEW_HISTORY_ADAPTER_CONTRACT_001.md`
- `PERSISTED_REVIEW_RECORD_SCHEMA_001.md`

## Scope and conclusion

The candidate outcome vocabulary is broadly aligned with the contract's required distinctions. It should not yet be translated directly into finalized Lean declarations: a few evidence and lineage semantics need to be made explicit first. This review does not select a backend, encoding, or storage guarantee.

## Six-Part Integrity Check

1. **Identity and retry outcomes — aligned, with one boundary to preserve.** The separate `AmbiguousIdentity` result matches the contract: do not select a matching record by list order. Candidate event IDs are diagnostic evidence, not proof that the candidate set is complete. Full-history uniqueness still depends on lookup completeness and backend enforcement.

2. **Commit receipt and payload boundary — clarify.** The schema explicitly separates adapter commit metadata from the event payload. Keep `CommitReceipt` and post-commit backend evidence outside the immutable logical payload unless a separately reviewed event model says otherwise. A receipt's `position` must be assigned by the backend's documented ordering/commit operation, never inferred from a local list or guessed sequence.

3. **Not-committed evidence and retry safety — under-specified.** The contract permits `NotCommitted` only when authoritative evidence establishes non-commit and the same-key retry cannot create a duplicate. The candidate `NotCommitted(evidence)` should name this requirement explicitly. Evidence that is merely “no matching record found” is insufficient when the read is incomplete, stale, unauthorized, or non-snapshot.

4. **History page semantics — aligned, but distinguish page coverage from global completeness.** `Completeness` and `Consistency` appropriately expose unknowns. Define completeness relative to the requested branch/scope and page/cursor contract; a page being fully returned must not imply that the entire history is complete. Cursor expiry and snapshot guarantees remain adapter-specific.

5. **Verification and provenance — aligned, with schema details open.** `valid`, `invalid`, `incomplete`, `unsupportedSchema`, and `unavailable` remain meaningfully distinct. A report should identify checks and scope. Reconstructed and reassessed results must not be presented as the original stored assessment. Diagnostic codes, severity vocabulary, schema migration, and rule-snapshot references remain open.

6. **Fork lineage observability — type-level gap.** The mapping to IT-5 requires source/destination identity, anchor availability, destination genesis identity, and incomplete-lineage findings, but the candidate vocabulary does not yet define a lineage/fork result structure that carries these fields. Do not claim the type design fully covers IT-5 until it either identifies the existing persisted event/lineage record as the carrier or proposes a separate explicit lineage result.

## Recommended next action

Revise the candidate design in place to clarify items 2–4 and resolve item 6 by explicitly mapping each IT-5 datum to an existing logical record field or a proposed field. Keep any added field provisional. Then perform a field-by-field crosswalk against the persisted-record schema and adapter contract before changing Lean types.

## Status boundary

- **Retrieved:** all three named source documents.
- **Reviewed:** semantic alignment and the six findings above.
- **Proposed:** clarifications and the next action.
- **Not done:** no Lean types changed; no adapter implemented; no integration tests executed; no project-wide policy adopted; no backend or durability behavior verified.

