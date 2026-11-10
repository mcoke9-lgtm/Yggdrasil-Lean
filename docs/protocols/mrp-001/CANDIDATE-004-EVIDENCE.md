# Candidate-004 Evidence and Provenance Register

**Related protocol:** MRP-001 Working Baseline 2026-11, Revision 4  
**Candidate status:** Frozen, unchanged, not adopted as the implementation  
**Candidate-005:** Not authorized  
**Implementation work:** Not authorized

## 1. Purpose

This register preserves the available provenance and verification claims for Candidate-004. Candidate-004 is evidence that selected MRP-001 capabilities were implemented and tested; it is not the adopted protocol and is not designated as the reference implementation.

## 2. Source artifacts and integrity values

| Artifact | SHA-256 |
|----------|---------|
| `mrp_records.py` | `4f5d58181f3637d9565bac7adf231e3a9bdb9e16bd1873158038bf400d977583` |
| `test_mrp_records.py` | `515cc4fa3b2d980ba6f0ac1668cbb6ae51a447490082f26ac22a3a915607b2e8` |

These are the source and test hashes recorded in the project evidence history. This publication does not independently recompute the hashes from repository files.

## 3. Candidate API recorded in the evidence history

- `create_record(content, metadata=None) -> (record_id, version_id)`
- `revise(record_id, content, parent_version_ids, metadata=None, conflict_with=None) -> version_id`
- `get_version(version_id)`
- `get_record_versions(record_id)`
- `get_tips(record_id)`
- `list_conflicts(record_id)`

## 4. Reported implementation properties

Candidate-004 was reported to implement:

- Collision-checked record/version identifiers using a shared registry and bounded retry.
- Metadata type validation in `create_record` and `revise`, before content JSON validation.
- Deep-copy mutation isolation.
- Strict JSON validation rejecting NaN and positive/negative infinity.
- Duplicate-parent normalization.
- Distinction between structural divergence (multiple tips) and explicit conflict annotations.
- Directional conflict annotations.
- In-memory storage only.

These are candidate properties. Under the adopted MRP-001 baseline, strict JSON and directional conflict semantics are mandatory protocol requirements; the other listed implementation details remain quality attributes, not mandatory minimal-profile requirements.

## 5. Test and review evidence

The retained project evidence reports:

| Evidence stage | Reported result | Qualification |
|----------------|-----------------|---------------|
| Source received | Complete source and test files supplied | Historical project record |
| Static review | Passed | Review record; not repeated for this publication |
| Contributor-reported tests | 16/16 passed | Contributor-reported execution |
| Independent reproduction | 16/16 passed; exit status 0; 0.004 seconds | Reported in a supplied independent execution report |
| Independent environment | Python 3.12.3; Ubuntu 24.04.4 LTS; Linux 6.12.8+; x86_64; standard library only | Environment stated in the independent report |
| Hash comparison | Same source and test hashes reported | Not recomputed by this publication process |
| Integration assessment | Received and reviewed | Historical project record |

**Verification limitation:** The publication process did not execute Candidate-004 or its tests. The 16/16 result is recorded as reported independent reproduction evidence, not as a test run performed by the publisher in this publication session.

## 6. Relationship to MRP-001 acceptance criteria

Candidate-004's 16 regression tests are candidate-specific evidence. They are not a separately adopted protocol-level conformance suite.

The project evidence history maps Candidate-004's behavior to the minimal protocol capabilities and to strict JSON and directional conflict semantics. The existence of this candidate and its reported tests does not establish that every future implementation conforms to MRP-001.

## 7. Frozen status and change control

- Candidate-004 is frozen and must remain unchanged.
- This publication package does not copy, modify, reformat, or replace Candidate-004 source or tests.
- Candidate-004 is not adopted as the implementation.
- Candidate-005 has not been authorized or started.
- Any future implementation work requires separate explicit authorization.

## 8. Evidence categories

The project distinguishes:
- Source received.
- Static review.
- Contributor-reported execution.
- Independent reproduction.
- Integration assessment.
- Adoption decision.

These categories must not be collapsed. In particular, a reported independent reproduction is not the same as a test run performed during this publication.

## 9. Outstanding evidence and limitations

- The publication process has not independently recomputed the listed SHA-256 values from Candidate-004 files.
- The publication process has not rerun the 16 tests.
- Candidate-004 is in-memory only; durable persistence is not demonstrated by this candidate.
- Candidate-specific regression tests do not constitute the complete future protocol conformance suite.
- Future acceptance tests for durable round-trip, provenance, events, integrity seals, damaged-history representation, and import/export conflict policy remain proposed and unexecuted.

## 10. Publication metadata

- Repository: `mcoke9-lgtm/Yggdrasil-Lean`
- Publication branch: `publication/mrp-001-revision-4-adopted-2026-11`
- Related baseline: `docs/protocols/mrp-001/MRP-001.md`
- Related adoption record: `docs/protocols/mrp-001/MRP-001-ADOPTION.md`
