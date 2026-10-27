# AF-RUNTIME-001 — Reference Resolver

Status: read-only reference implementation and regression suite on feature branch `af-runtime-001-reference-resolver`; pull request #2 is draft and unmerged.

The package resolves review-history relationships from a caller-supplied snapshot. The Airtable adapter maps field dictionaries into the model without making network requests or writing records. An opt-in integration test can fetch a fresh snapshot through Airtable's read-only REST endpoint when explicit environment credentials are supplied.

## Implemented semantics

- Record IDs are unique within the supplied snapshot.
- Branch ID plus sequence must be unique; sequence is a non-negative integer.
- Parent record ID identifies the global review-history chain.
- Branch ID plus sequence separately identifies the branch-local predecessor.
- Source branch plus source anchor identifies a fork origin and checks that the anchor belongs to the declared source branch.
- Revision target is resolved independently of parent history.
- Missing records and references, malformed source references, source-branch mismatch, duplicate IDs or sequences, and parent cycles have stable error codes.
- Empty optional references are treated as absent only when represented by null or an empty string. This is not evidence that the original record explicitly declared NOT_APPLICABLE.
- The Airtable field adapter rejects malformed IDs, sequences, and whitespace-padded references. It does not silently repair data.

## Run offline regression tests

From the repository root:

    PYTHONPATH=runtime python -m unittest discover -s runtime/tests -v

The GitHub Actions workflow runs this command on the feature branch and pull requests to `main`. The live Airtable tests are skipped unless all three environment variables below are set.

## Optional live, read-only Airtable test

Set these environment variables in a trusted environment; never commit the token:

- `AIRTABLE_PAT`: a personal access token with read-only access to the pilot base.
- `AIRTABLE_BASE_ID`: the intended base ID.
- `AIRTABLE_TABLE_ID`: the Records table ID.

Then run the same offline regression command. `runtime/tests/test_live_airtable.py` makes GET requests only, follows Airtable pagination, verifies that AF-PILOT-001 through AF-PILOT-010 are present, and compares key live relationship outcomes. No create/update/delete requests are implemented. Use a narrowly scoped token and avoid exposing it in logs.

A CI pass with these variables absent verifies the offline suite only; the live tests will be reported as skipped, not passed. A live test has not been claimed unless the run output confirms it executed.

## Scope limits

- The offline fixture was transcribed from a prior live read; it is not itself a live adapter test.
- Freshness and completeness are established only to the extent that the live read completes pagination and passes the explicit expected-record checks.
- The resolver does not infer or repair missing links.
- This is a reference implementation, not production deployment.
