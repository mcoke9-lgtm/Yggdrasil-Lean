# AF-RUNTIME-001 — Reference Resolver

Status: initial implementation on a feature branch; tests are awaiting an actual execution run.

This is a pure, read-only reference implementation of the adopted review-history relationship contract. It does not connect to Airtable, mutate records, or prove connector retrieval completeness.

## Implemented semantics

- Record IDs are unique within the supplied snapshot.
- Branch ID plus sequence must be unique; sequence is a non-negative integer.
- Parent record ID identifies the preceding event in the global review-history chain.
- Branch ID plus sequence separately identifies the branch-local predecessor.
- Source branch plus source anchor identifies a fork origin and checks that the anchor belongs to the declared source branch.
- Revision target is resolved independently of parent history.
- Missing records and references, malformed source references, source-branch mismatch, duplicate IDs or sequences, and parent cycles have stable error codes.
- Empty optional references are treated as absent only when represented by null or an empty string. This is not evidence that the original record explicitly declared NOT_APPLICABLE.

## Run tests

From the repository root:

    PYTHONPATH=runtime python -m unittest discover -s runtime/tests -v

The command and test cases are committed, but no pass claim should be made until the command is run and its output is inspected.

## Scope limits

- The caller supplies the snapshot; completeness and freshness are not established by this package.
- The resolver validates parent cycles across the supplied snapshot.
- It does not infer or repair missing links.
- This is a reference implementation, not production deployment.
