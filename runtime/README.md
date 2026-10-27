# AF-RUNTIME-001 — Reference Resolver

Status: read-only reference implementation and regression suite on feature branch \`af-runtime-001-reference-resolver\`; pull request #2 is draft and unmerged.

The package resolves review-history relationships from a caller-supplied snapshot, provides a snapshot-wide audit, and includes a deterministic alternative-work evaluator. The Airtable adapter maps field dictionaries into the model without making network requests or writing records. An opt-in integration test can fetch a fresh snapshot through Airtable's read-only REST endpoint when explicit environment credentials are supplied.

## Implemented semantics

- Record IDs are unique within the supplied snapshot.
- Branch ID plus sequence must be unique; sequence is a non-negative integer.
- Parent Record ID represents the global review-history link.
- Branch ID plus sequence separately identifies the branch-local predecessor; it must not be substituted for a missing parent link.
- Source branch plus source anchor identifies a fork origin and checks that the anchor belongs to the declared source branch.
- Revision target is resolved independently of parent history.
- Each optional relationship returns a state: \`linked\` or \`blank_unclassified\`. Blank fields are not silently promoted to explicit N/A or proof of a root.
- Branch predecessor state is either \`found_in_snapshot\` or \`none_found_in_snapshot\`; the latter does not prove that the record is a branch root if the snapshot may be partial.
- Missing records and references, malformed source references, source-branch mismatch, duplicate IDs or sequences, and parent cycles have stable error codes.
- Parent-chain errors are scoped to the requested record's chain; unrelated broken branches do not block a valid resolution request.
- The Airtable field adapter rejects malformed IDs, sequences, and whitespace-padded references. It does not silently repair data.
- The live pilot read returned no Parent Record ID for AF-PILOT-007. Tests preserve that absence as \`blank_unclassified\` instead of inferring AF-PILOT-006 as its parent; the branch-local predecessor is separately AF-PILOT-006.

## Alternative-work evaluator (AF-AWR-001 v0.1.0)

Use `evaluate_work(WorkRequest(...))` for a side-effect-free recommendation when an operation is blocked. It preserves branch/checkpoint, validates input, applies hard relevance/feasibility/authorization/non-duplication/integrity gates, ranks eligible alternatives deterministically, permits a retry only when materially changed and within budget, and only emits `STOP_NO_ALTERNATIVE` when the search scope is declared exhausted. Exact ranking ties are returned together. The result explicitly says execution was not attempted and persistence was not performed. This is a reference evaluator, not a background agent or a production orchestration service.

## Snapshot-wide audit

Use `audit_snapshot(records)` to inspect every record's parent, source-anchor, and revision relationships, rather than resolving a single target. It reports missing links, source-branch mismatches, duplicate IDs/sequences, malformed records, and parent cycles. Blank references are reported as informational `BLANK_UNCLASSIFIED` findings; they do not become explicit roots or N/A values. The report status fails on errors but may pass with unclassified blanks, so callers must inspect both `status` and `unclassified_count`. Coverage is separately labelled `unknown`, `declared_partial`, or `declared_complete`; the declaration is caller-supplied and is not independent proof of completeness. A structurally passing partial or unknown snapshot must not be treated as a complete-history verification.

## Run offline regression tests

From the repository root:

    PYTHONPATH=runtime python -m unittest discover -s runtime/tests -v

The GitHub Actions workflow runs this command on the feature branch and pull requests to \`main\`. The live Airtable tests are skipped unless all three environment variables below are set.

## Optional live, read-only Airtable test

Set these environment variables in a trusted environment; never commit the token:

- \`AIRTABLE_PAT\`: a personal access token with read-only access to the pilot base.
- \`AIRTABLE_BASE_ID\`: the intended base ID.
- \`AIRTABLE_TABLE_ID\`: the Records table ID.

Then run the same offline regression command. \`runtime/tests/test_live_airtable.py\` makes GET requests only, follows Airtable pagination, verifies that AF-PILOT-001 through AF-PILOT-010 are present, and compares key live relationship outcomes. No create/update/delete requests are implemented. Use a narrowly scoped token and avoid exposing it in logs.

A CI pass with these variables absent verifies the offline suite only; the live tests will be reported as skipped, not passed. A live test has not been claimed unless the run output confirms it executed.

## Scope limits

- The offline fixture was transcribed from a prior live read; it is not itself a live adapter test.
- Freshness and completeness are established only to the extent that the live read completes pagination and passes the explicit expected-record checks.
- The resolver does not infer or repair missing links.
- This is a reference implementation, not production deployment.
