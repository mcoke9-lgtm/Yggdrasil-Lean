# MRP-001 Adoption and Decision Record

**Protocol:** MRP-001 Working Baseline 2026-11  
**Revision:** 4  
**Adoption status:** Adopted  
**Adoption date:** 2026-11-10  
**Adopted artifact:** `docs/protocols/mrp-001/MRP-001.md`

## 1. Adoption decision

On 2026-11-10, the user explicitly answered **“Y”** to the question:

> Do you explicitly adopt MRP-001 Working Baseline 2026-11, Revision 4 — Final Adoption Candidate, exactly as presented above, including A9 and A10?

This constitutes the formal adoption decision for Revision 4, including the two editorial clarifications A9 and A10, as presented in the conversation.

The adopted text was supplied in full in the conversation immediately before the adoption decision. This publication records that text; it does not claim that a separate pre-existing final file had been recovered.

## 2. Editorial clarifications included in the adopted text

### A9 — Strict JSON

Content and metadata, when present, MUST be serializable under the JSON data model. Implementations MUST reject values not representable in JSON, including but not limited to NaN, positive or negative infinity, functions, and non-finite floating-point values. Validation applies recursively to nested arrays and objects. Rejection occurs before a version is created or revised.

### A10 — Directional conflict annotations

Conflict annotations are directional. Reciprocal annotations are not required. Conformance is demonstrated by storing and later retrieving a one-way conflict annotation without requiring or automatically creating the reverse annotation.

## 3. Six authorized policy decisions

The following six decisions were authorized before formal adoption and are preserved in Revision 4:

| # | Decision | Authorized position |
|---|----------|---------------------|
| 1 | Standard-library-only / no-cloud | Reference-implementation profile only |
| 2 | Deep-copy, strict JSON, metadata order | Strict JSON elevated to a mandatory protocol requirement; deep-copy isolation and metadata-validation order remain quality attributes |
| 3 | Directional conflicts | Normative; reciprocity not required |
| 4 | Durable persistence | Optional higher profile |
| 5 | Compatibility policy | Additions are non-breaking only when backward compatibility is preserved, including appropriate handling of unknown optional fields; removal or semantic alteration of inherited or adopted requirements is breaking |
| 6 | Orphaned external references | Deferred to future recovery/import work |

## 4. Adoption boundaries

Adoption establishes the MRP-001 Revision 4 protocol baseline. It does not establish that a complete conforming implementation exists.

- Candidate-004 remains frozen, unchanged, and **not adopted as the implementation**.
- Candidate-005 is **not authorized**.
- Implementation work is **not authorized** by this adoption.
- The reference-implementation profile remains non-normative.
- The durable profile remains optional.
- Proposed extension areas and unresolved issues remain outside minimal-profile conformance unless separately specified in a future authorized baseline or profile.

## 5. Publication authorization and scope

The user separately authorized publication of the MRP-001 package to the existing `mcoke9-lgtm/Yggdrasil-Lean` repository on a separate branch, preserving repository history. That publication authorization is being carried forward after formal adoption and repository/write-access verification.

The authorized package consists of:
1. The complete adopted normative baseline.
2. This adoption and decision record.
3. The Candidate-004 evidence and provenance register.

Publication does not authorize implementation, modification of Candidate-004, or creation of Candidate-005.

## 6. Historical and evidence integrity

The source-recovery process initially found the complete adopted artifact was not available as a standalone file and found no explicit adoption statement in the earlier recoverable history. The complete Final Adoption Candidate was subsequently presented in full in the conversation and explicitly adopted by the user on 2026-11-10.

Accordingly:
- The adoption date recorded here is the date of the explicit adoption decision.
- No earlier adoption date is asserted.
- The publication file is the adopted candidate text supplied in the conversation, not a claim that an earlier standalone artifact was recovered.
- Evidence claims about Candidate-004 are limited to the project evidence record and are not represented as tests rerun during this publication.

## 7. Publication metadata

- Repository: `mcoke9-lgtm/Yggdrasil-Lean`
- Publication branch: `publication/mrp-001-revision-4-adopted-2026-11`
- Baseline branch: `main`
- Protocol version: `MRP-001 Working Baseline 2026-11, Revision 4`
- Adoption date: `2026-11-10`

Commit SHAs and file integrity values are recorded in the publication report and/or repository history after the package is complete.
