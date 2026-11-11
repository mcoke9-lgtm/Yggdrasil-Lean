# THO-015 — Probe 4 Corrected Case-Distinct Record-ID Test

**Artifact:** THO-015-P4-SUP-001  
**Status:** TESTED — corrected harness passed its assertions in hosted CI  
**Branch:** `tho-015/probe1-ci-bootstrap`  
**Harness commit:** `b0c895e5c74cbbca3469e4f5ee32d95b0094cd52`  
**Workflow run:** https://github.com/mcoke9-lgtm/Yggdrasil-Lean/actions/runs/43016855075  
**Runtime:** GitHub-hosted Ubuntu runner; Python 3.13 (workflow setup)

## Purpose

Correct the earlier Probe 4 limitation: the prior test paired a case-distinct record identifier with a version identifier and therefore did not demonstrate that two Core Memory record IDs differing only by case could coexist in the same snapshot.

## Corrected test

The revised harness constructs one valid Core Memory snapshot containing two separate records:
- `Legacy-ID`
- `legacy-id`

Each record has its own distinct version ID and an independent initial version. The test:
1. imports the combined snapshot with `MemoryStore.from_snapshot`;
2. serializes the imported store with `to_snapshot`;
3. imports the serialized snapshot again;
4. verifies both exact record identifiers remain present and addressable independently;
5. retains an exact duplicate record-ID rejection control;
6. retains the separate provenance event-ID case-distinction check and the other legacy-identifier observations.

The test script exits successfully only if the case-distinct records both survive import and round-trip, the exact duplicate control is rejected, and the other expected observations hold.

## Result

The dedicated THO-015 workflow completed the corrected Probe 4 step with conclusion **success** on run 43016855075. Probe 1 also completed successfully. The overall workflow concluded **failure** because Probe 2 continues to return its previously recorded malformed-history precedence failure; this does not negate the separately successful Probe 4 step.

**Corrected finding:** the tested Core Memory importer accepts two record IDs differing only by case in the same snapshot and preserves both through a serialization/import round trip, on the tested Ubuntu/Python runtime.

## Limits

- This establishes behavior for the tested synthetic identifiers and runtime, not proof that these identifiers occur in actual historical user data.
- It does not establish Windows filesystem behavior or safety for identifiers used as path components.
- It does not define a future creation-time identifier policy.
- It does not change or expand the adopted L2 grandfathering decision.
- No YIM-001 source, production data, adopted decision, or normative specification was modified.
