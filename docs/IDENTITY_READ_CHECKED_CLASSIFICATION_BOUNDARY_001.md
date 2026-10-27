# Identity-Read Checked Classification Boundary 001

Status: proposed design and test matrix; not implemented or executed. This document does not change the public API.

## Purpose

Close three gaps found in review of `IdentityReadOutcome` without pretending that a Lean type can prove a remote backend searched exhaustively:

1. Ambiguity can currently be constructed with fewer than two distinct observed IDs.
2. `found` and `notFound` accept a generic `CompleteEvidence` whose type alone does not establish completeness.
3. Candidate ID lists may contain duplicates, and no classifier currently derives a result from evidence.

The design separates **classification of observations** from **trusted backend attestation** and from **record/schema validation**.

## Boundary model

Use three layers, in this order:

1. **Observation normalization (pure Lean):** given `IdentityLookupEvidence EventId` and `[DecidableEq EventId]`, derive the distinct observed candidate IDs. Duplicate appearances of one ID count as one identity; list order never chooses a winner.
2. **Outcome classification (pure Lean):** derive the evidence-level state from normalized distinct IDs, completeness, and termination. Callers do not choose the classification constructor.
3. **Trusted adapter validation (backend-specific):** only after a successful, complete lookup over the declared scope may the adapter issue the marker used by `found` or `notFound`. The adapter must document and test how it warrants that claim. A Boolean or generic marker passed in by an arbitrary caller is not a proof of exhaustiveness.

Record validation (schema, integrity, provenance) is a separate check. A unique candidate ID alone does not prove that the record is valid or that the observed record matches the requested identity.

## Proposed classification precedence

The classifier should return a dedicated intermediate type, not construct `IdentityReadOutcome` directly:

1. **Two or more distinct candidate IDs → ambiguous.** This takes precedence over termination and completeness, including `failedAfterObservations`. Preserve all observed IDs and the original termination/completeness evidence. Never select by list order.
2. **No established ambiguity and termination is `failedAfterObservations` → unavailable-with-observations.** Keep the evidence; do not call it complete, not-found, or found.
3. **No established ambiguity and termination is `stoppedEarly` → incomplete.**
4. **Termination is `completed` and completeness is `incomplete` → incomplete.**
5. **Termination is `completed` and completeness is `unknown` → unresolved.**
6. **Termination is `completed` and completeness is `complete` → complete candidate set.** This is only an intermediate classification. The trusted adapter boundary must still validate its attestation before it can construct public `found` or `notFound`.

For defensive consistency, a claimed `complete` scope paired with `stoppedEarly` or `failedAfterObservations` must not yield complete evidence. Rule 2 or 3 applies. The classifier should preserve evidence rather than silently rewrite it.

The complete candidate set has zero, one, or more distinct IDs. The two-or-more case has already been classified as ambiguous. Zero may support `notFound` only after trusted completeness validation. One may support `found` only after trusted completeness validation and record validation.

## Type-shape options to evaluate before API changes

**Preferred direction:** introduce an intermediate classification inductive whose constructors distinguish `completeCandidates`, `incomplete`, `unresolved`, `ambiguous`, and `unavailableAfterObservations`. The complete-candidate constructor should be reachable only through the classifier, and public outcome construction should be concentrated in a checked adapter boundary.

This is not yet a proof that arbitrary external code cannot forge a complete marker. Lean module visibility and the exact placement of constructors/functions must be reviewed. If the public API must remain freely constructible, document that it is a vocabulary type rather than a safety boundary.

Do not use `List.eraseDups` without first checking its equality assumptions and behavior in the repository's Lean version. Any normalized list is for classification only; preserve the original evidence list for diagnostics if duplicates themselves are relevant.

## Required unit/property tests (proposed, not implemented)

| ID | Input condition | Required classification |
|---|---|---|
| C-1 | Complete + completed + zero distinct IDs | Complete candidate set with zero IDs; not yet public `notFound` |
| C-2 | Complete + completed + one distinct ID | Complete candidate set with one ID; not yet public `found` |
| C-3 | Same ID observed repeatedly | One distinct identity, never ambiguous |
| C-4 | Two distinct IDs in either list order | Ambiguous in both orders |
| C-5 | Two distinct IDs + failure after observations | Ambiguous; retain failed termination and both IDs |
| C-6 | Zero/one distinct ID + failure after observations | Unavailable-with-observations |
| C-7 | Stopped early, zero/one distinct ID | Incomplete |
| C-8 | Completed + unknown completeness, zero/one distinct ID | Unresolved |
| C-9 | Completed + incomplete completeness, zero/one distinct ID | Incomplete |
| C-10 | Complete marker proposed for a stopped/failed lookup | Rejected by the checked boundary; never `found`/`notFound` |
| C-11 | Unique candidate but corrupt or unsupported record schema | Corrupt/unsupported-schema path, not `found` |
| C-12 | Same evidence list reversed | Same classification and same set of distinct IDs |

Tests C-1 through C-9 target the pure classifier. C-10 requires boundary tests. C-11 requires record/schema validation tests. C-12 is a permutation-invariance property. These are specifications, not results.

## Relationship to existing IT-6

Add these cases to the existing IT-6 identity-read scenarios. The current IT-6 list is a test specification only; it is not implemented or executed. Backend integration tests must separately demonstrate that the adapter's completeness attestation corresponds to the actual query scope, pagination, consistency model, and failure behavior.

## Explicit non-claims

- A pure classifier can count distinct IDs among observations; it cannot prove that all matching records were observed.
- A `CompleteEvidence` marker is not self-authenticating merely because it has a distinct type parameter.
- This design has not been compiled, implemented, or tested.
- The existing PR remains a draft proposal until API shape and visibility are reviewed.

## Six-Part Integrity Check

1. **Count:** twelve proposed checks, C-1 through C-12.
2. **Roles:** cardinality, duplicate normalization, ambiguity precedence, failure preservation, incomplete/unknown handling, trust-boundary enforcement, record validation, and order independence.
3. **Completeness:** includes pure-classifier, boundary, and record-validation cases; actual backend completeness semantics remain backend-specific and unresolved.
4. **Status:** design proposed; tests specified; no implementation or test execution; not adopted as final API.
5. **Recheck:** ambiguity has priority over later failure; complete candidate classification is explicitly not equivalent to trusted completeness; list order cannot select a winner.
6. **Persistence:** this document is intended for the PR branch; no source API changes are made here. Remaining uncertainty: module visibility/constructor access design and the concrete backend attestation mechanism.
