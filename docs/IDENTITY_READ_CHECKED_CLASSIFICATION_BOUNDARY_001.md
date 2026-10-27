# Identity-Read Checked Classification Boundary 001

Status: classifier and a defensive identity-read policy gate are implemented; regression theorems cover both. GitHub Actions build verification for the newest gate commit is pending. The Bool attestation inputs are adapter assertions, not a capability-security boundary; backend integration remains unimplemented.

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

### Checked-boundary recommendation and implemented policy gate

`gateIdentityRead` now separates evidence classification from a final candidate decision. It refuses to authorize candidate results when `scopeAttested = false`, rejects a single candidate when `recordMatchesRequestedIdentity = false`, and permits a zero-candidate result only after the scope-attestation flag is true. Regression theorems cover these cases.

**Important limitation:** the two flags are ordinary `Bool` inputs. This gate is a defensive policy function, not a capability-security boundary; any caller can pass `true`. A concrete adapter must keep this function behind its own trusted implementation and derive those flags from actual backend completion, declared scope, schema checks, and requested-identity validation. A future API may use module-controlled attestation types, but a marker type alone is not proof that a remote query was exhaustive.

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

Tests C-1 through C-9 target the pure classifier. The current theorem file now covers duplicates, distinct IDs in both orders, ambiguity precedence over failure, failure without ambiguity, early stop, incomplete/unknown coverage, and complete-candidate outcomes for empty/single-identity observations. C-10 still requires boundary tests. C-11 requires record/schema validation tests. C-12 is only partially covered: reversed competing IDs are independently classified ambiguous, but a general permutation-invariance property remains unproven.

## Relationship to existing IT-6

Add these cases to the existing IT-6 identity-read scenarios. The current IT-6 list is a test specification only; it is not implemented or executed. Backend integration tests must separately demonstrate that the adapter's completeness attestation corresponds to the actual query scope, pagination, consistency model, and failure behavior.

## Explicit non-claims

- A pure classifier can count distinct IDs among observations; it cannot prove that all matching records were observed.
- A `CompleteEvidence` marker is not self-authenticating merely because it has a distinct type parameter.
- The pure classifier and its regression theorems are compiled by the Lean build workflow; this is not backend integration testing.
- The policy gate is implemented, but trusted backend attestation is not; public outcome constructors remain freely constructible.
- The existing PR remains a draft proposal until API shape and visibility are reviewed.

## Six-Part Integrity Check

1. **Count:** twelve specified checks, C-1 through C-12; current theorem file contains thirteen classifier regression theorems.
2. **Roles:** cardinality, duplicate normalization, ambiguity precedence, failure preservation, incomplete/unknown handling, trust-boundary enforcement, record validation, and order independence.
3. **Completeness:** pure classifier cases are partially implemented and build-checked; checked-boundary and record-validation cases remain unimplemented; actual backend completeness semantics remain backend-specific and unresolved.
4. **Status:** classifier and defensive policy gate implemented; gate theorem checks added; newest CI verification pending; trusted adapter enforcement not implemented.
5. **Recheck:** ambiguity has priority over later failure; complete candidate classification is explicitly not equivalent to trusted completeness; list order cannot select a winner.
6. **Persistence:** implementation, tests, and design record are on the PR branch. Remaining uncertainty: trusted module/API boundary and concrete backend attestation mechanism.
