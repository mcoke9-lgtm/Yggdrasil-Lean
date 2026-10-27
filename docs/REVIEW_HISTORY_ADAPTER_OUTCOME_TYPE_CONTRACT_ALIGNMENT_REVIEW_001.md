# Review History Adapter Outcome Type Design — Contract Alignment Review 001

**Status:** Review findings and proposed clarifications; not adopted, not an implementation record  
**Reviewed artifact:** `docs/REVIEW_HISTORY_ADAPTER_OUTCOME_TYPE_DESIGN_001.md`  
**Related contracts:** `REVIEW_HISTORY_ADAPTER_CONTRACT_001.md`, `PERSISTED_REVIEW_RECORD_SCHEMA_001.md`, `DURABLE_HISTORY_DECISION_RECORD_001.md`, `REVIEW_HISTORY_ADAPTER_INTEGRATION_TEST_SPEC_001.md`

## Review result

The candidate design is directionally aligned with the adapter contract: it keeps uncertain commit, identity ambiguity, corruption, unsupported schema, unavailable service, and incomplete evidence distinguishable. It correctly states that types expose observability but do not prove backend guarantees.

Before translating the proposal into Lean declarations, resolve the following semantic mismatches. These are proposed clarifications, not adopted policy.

## Findings

### R1 — Define the evidence threshold for `NotCommitted`

The adapter contract says `NotCommitted` requires evidence establishing that the write did not commit and that retry is safe under the documented contract. The outcome-type draft uses `NotCommitted(evidence)`, but does not constrain what qualifies as evidence.

**Proposed clarification:** require a documented reconciliation basis/scope and an explicit retry-safety conclusion. If absence cannot be established within that scope, return `StillUnknown`, not `NotCommitted`. A successful negative lookup is insufficient unless the backend contract makes that lookup authoritative for the uncertain write.

### R2 — Keep history-page completeness scoped

The draft's `Completeness = complete | incomplete | unknown` could be read as completeness of the whole branch history, while the contract describes a page/segment and cursor traversal. These are not equivalent.

**Proposed clarification:** name and document the scope (for example, page, traversal, or entire branch history). Do not infer global uniqueness or complete recovery from a page marked complete. Multi-page snapshot consistency remains a separate dimension.

### R3 — Align position and sequence terminology

The receipt contains `position`; the persisted schema contains `sequence`; the decision record leaves sequence base/head semantics open.

**Proposed clarification:** treat receipt position as the backend-assigned position for the committed event, and explicitly map it to the schema's sequence only after the sequence convention is chosen. Do not add a default base or imply that position is globally comparable across branches.

### R4 — Avoid duplicate or contradictory retryability sources

The draft allows `retryability?` inside `Diagnostic`, while the contract describes retryability as part of `Unavailable` and requires it to be known rather than guessed. Uncoordinated fields could disagree.

**Proposed clarification:** choose one authoritative representation or state a validation invariant requiring them to agree. Use `unknown` when evidence is insufficient; do not derive safe retry from the outcome constructor alone.

### R5 — Keep operational failures separate from verification findings

Append/read outcomes and verification outcomes answer different questions. A storage outage is an operational inability to verify; a failed invariant is a finding; missing evidence is incomplete. The draft mostly preserves this but should state the cross-operation rule explicitly.

**Proposed clarification:** `unavailable` means the verification could not be completed due to an operational limitation; `incomplete` means the declared verification scope lacked required evidence; `invalid` means a specified invariant was disproved. Corrupt/unsupported-schema details should be surfaced without silently converting them into `valid` or an ordinary `NotFound`.

### R6 — Bound diagnostic candidate lists

The draft correctly says candidate event IDs are diagnostic evidence and may not be complete. A caller could nevertheless mistake the list for the full set of duplicates.

**Proposed clarification:** label candidate lists as observed candidates, include lookup scope/completeness where available, and never let a partial list resolve `AmbiguousIdentity`. Access-control and disclosure policy remains open.

## Decision boundary

No finding here selects a backend, final Lean constructor shape, identifier scheme, canonical encoding, digest, retention policy, or global adoption. The next design revision should address R1–R6, then undergo a focused consistency pass against the adapter contract and integration-test specification before Lean types are changed.

## Six-Part Integrity Check

1. **Count:** Exactly six findings, R1–R6.
2. **Role mapping:** R1 reconciliation evidence; R2 history scope; R3 sequence/receipt mapping; R4 retryability authority; R5 verification semantics; R6 ambiguity diagnostics.
3. **Completeness:** All six findings target contract/schema alignment. Backend durability, atomic compare-and-append, global uniqueness, encoding, threat model, and lifecycle remain separate unresolved implementation gates.
4. **Status:** Review findings and proposed clarifications only; none adopted; no Lean types changed; no integration tests executed by this review.
5. **Recheck:** Distinctions between conflict, ambiguity, corruption, unsupported schema, unavailable, incomplete, invalid, and unknown commit are retained.
6. **Persistence/uncertainty:** This note records review findings if committed. It does not establish that the contract has been amended or the proposals accepted.
