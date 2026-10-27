# Review History Adapter Outcome Design Crosswalk Review 001

**Status:** Review finding recorded; recommendations only; no policy adopted.
**Reviewed artifacts:** `REVIEW_HISTORY_ADAPTER_OUTCOME_TYPE_DESIGN_001.md`, `REVIEW_HISTORY_ADAPTER_CONTRACT_001.md`, and `PERSISTED_REVIEW_RECORD_SCHEMA_001.md`.
**CI context:** Outcome-design workflow run #96 completed successfully (build job). This verifies the repository workflow, not the design's runtime behavior or adapter implementation.

## Findings

1. **Append outcomes align, with one naming boundary to preserve.** The candidate append vocabulary covers committed, already committed, conflict, ambiguous identity, unavailable, corrupt, and commit unknown. Keep `CommitUnknown` distinct from an unavailable service: uncertainty about whether a write committed is a different fact from inability to reach a service.

2. **Read-by-identity outcomes are not fully crosswalked.** The contract requires `Found(record, provenance)`, `NotFound`, `UnsupportedSchema`, `Corrupt`, and `Unavailable`. The candidate vocabulary discusses provenance and failure outcomes, but does not define the identity-read result family. Add it to the crosswalk before treating the design as complete; `NotFound` must mean absence within a declared lookup scope, not an access or service failure.

3. **Ordered-history outcomes need an explicit mapping.** The contract's `Page`, `Empty`, and `GapOrConflict` outcomes should map to page metadata and explicit empty/gap semantics. `Completeness = complete | incomplete | unknown` is useful but does not by itself say whether a gap, identity conflict, or broken lineage was detected. Keep the existing broad `gapOrConflict` boundary unless a separate structured reason vocabulary is reviewed.

4. **Reconciliation evidence needs a defined absence claim.** `NotCommitted(evidence)` is stronger than `NotFound`: it must establish that the original append did not cross the documented commit boundary and that retrying under the original idempotency contract is safe. A lookup miss alone is insufficient unless its scope and consistency make that conclusion valid.

5. **Schema and integrity findings must stay bounded by evidence.** The persisted schema includes integrity slots but leaves canonical encoding and threat model unresolved. The design correctly defers digest claims. A verification report's `valid` result must name the checks and scope actually covered; unsupported schema and incomplete evidence must not collapse into `invalid`.

6. **Commit receipt and payload separation must be explicit.** The schema treats adapter commit metadata as separate from the event payload. The proposed receipt may refer to the committed event and backend evidence, but receipt metadata must not be retroactively treated as part of the event payload whose commit it describes. Receipt guarantees remain backend-specific and unresolved.

## Required crosswalk before translating to Lean

| Contract operation | Outcome distinctions to retain | Design status |
|---|---|---|
| Append | committed / already committed / conflict / ambiguous identity / unavailable / corrupt / commit unknown | Mostly mapped; semantics remain proposed |
| Read by event identity | found with provenance / scoped not-found / unsupported schema / corrupt / unavailable | Missing explicit result-family mapping |
| Read ordered history | page with completeness and consistency / complete empty / gap-or-conflict / unsupported / corrupt / unavailable | Partial mapping; gap reasons remain open |
| Reconcile uncertain commit | committed / evidence-backed not-committed / still unknown / ambiguous identity / conflict / corrupt / unavailable | Present conceptually; evidence threshold open |
| Verify record and lineage | valid / invalid / incomplete / unsupported schema / unavailable, with checks and scope | Present conceptually; checks and threat model open |
| Recover or reassess | original retrieval / reconstruction / linked new reassessment | Provenance labels proposed; persisted linkage still needs implementation |

## Six-Part Integrity Check

1. **Count:** Exactly six findings, each covering a distinct contract-to-design boundary.
2. **Role mapping:** (1) append, (2) identity read, (3) ordered history, (4) reconciliation, (5) verification/integrity, (6) commit metadata versus event payload.
3. **Completeness:** All six contract operations are represented in the crosswalk; no storage, concurrency, or digest guarantees are inferred from types.
4. **Status:** Review note proposed and recorded; no Lean types changed, no policy adopted, no adapter or integration tests implemented by this review.
5. **Recheck:** Scoped absence differs from failure; `NotCommitted` requires stronger evidence than a lookup miss; commit uncertainty remains distinct; integrity claims remain deferred.
6. **Persistence/uncertainty:** This note is a version-controlled review record. It does not establish project-wide adoption or runtime correctness.
