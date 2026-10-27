# AF-RUNTIME-001 — Reference Resolver

Status: read-only reference implementation and regression suite on feature branch `af-runtime-001-reference-resolver`; pull request #2 is draft and unmerged.

The package resolves review-history relationships from a caller-supplied snapshot, provides a snapshot-wide audit, and includes a deterministic alternative-work evaluator. The Airtable adapter maps field dictionaries into the model without making network requests or writing records. An opt-in integration test can fetch a fresh snapshot through Airtable's read-only REST endpoint when explicit environment credentials are supplied.

## Implemented semantics

- Record IDs are unique within the supplied snapshot.
- Branch ID plus sequence must be unique; sequence is a non-negative integer.
- Parent Record ID represents the global review-history link.
- Branch ID plus sequence separately identifies the branch-local predecessor; it must not be substituted for a missing parent link.
- Source branch plus source anchor identifies a fork origin and checks that the anchor belongs to the declared source branch.
- Revision target is resolved independently of parent history.
- Each optional relationship returns a state: `linked` or `blank_unclassified`. Blank fields are not silently promoted to explicit N/A or proof of a root.
- Branch predecessor state is either `found_in_snapshot` or `none_found_in_snapshot`; the latter does not prove that the record is a branch root if the snapshot may be partial.
- Missing records and references, malformed source references, source-branch mismatch, duplicate IDs or sequences, and parent cycles have stable error codes.
- Parent-chain errors are scoped to the requested record's chain; unrelated broken branches do not block a valid resolution request.
- The Airtable field adapter rejects malformed IDs, sequences, and whitespace-padded references. It does not silently repair data.
- The live pilot read returned no Parent Record ID for AF-PILOT-007. Tests preserve that absence as `blank_unclassified` instead of inferring AF-PILOT-006 as its parent; the branch-local predecessor is separately AF-PILOT-006.

## Alternative-work evaluator (AF-AWR-001 v0.1.0)

Use `evaluate_work(WorkRequest(...))` for a side-effect-free recommendation when an operation is blocked. It preserves branch/checkpoint, validates input, applies hard relevance/feasibility/authorization/non-duplication/integrity gates, ranks eligible alternatives deterministically, permits a retry only when materially changed, within budget, and supplied with a new operation fingerprint not present in the caller-provided prior fingerprint list. Missing or repeated fingerprints fail closed. It only emits `STOP_NO_ALTERNATIVE` when the search scope is declared exhausted. Dependency waits require an explicit dependency ID and report it separately from the candidate ID. Exact ranking ties are returned together. The result explicitly says execution was not attempted and persistence was not performed. The evaluator still trusts the caller to supply accurate fingerprints, prior-attempt history, material-change status, and search-scope exhaustion; it does not independently observe the world or maintain a durable attempt ledger. This is a reference evaluator, not a background agent or a production orchestration service.

## Snapshot-wide audit

Use `audit_snapshot(records)` to inspect every record's parent, source-anchor, and revision relationships, rather than resolving a single target. It reports missing links, source-branch mismatches, duplicate IDs/sequences, malformed records, and parent cycles. Blank references are reported as informational `BLANK_UNCLASSIFIED` findings; they do not become explicit roots or N/A values. The report status fails on errors but may pass with unclassified blanks, so callers must inspect both `status` and `unclassified_count`. Coverage is separately labelled `unknown`, `declared_partial`, or `declared_complete`; the declaration is caller-supplied and is not independent proof of completeness. A structurally passing partial or unknown snapshot must not be treated as a complete-history verification.

## Command-line use

After installing the package, `af-runtime` (or `python -m adaptive_filesystem_runtime`) accepts JSON on standard input and writes versioned JSON reports to standard output. It supports `audit`, `resolve`, and `evaluate`; it never contacts Airtable or persists decisions.

Examples:

    printf '%s' '{"records":[{"record_id":"a","branch_id":"main","sequence":1}],"coverage":"unknown"}' | af-runtime audit

    printf '%s' '{"record_id":"a","records":[{"record_id":"a","branch_id":"main","sequence":1}]}' | af-runtime resolve

    printf '%s' '{"request":{"request_id":"r1","active_branch":"main","checkpoint_id":"AF-PILOT-006","current_goal":"recover","blocked_operation":"live read","observed_failure":"credentials unavailable","blocked":true,"candidates":[]}}' | af-runtime evaluate

Invalid input or resolution failures produce a JSON error on standard error and a nonzero exit code. A valid evaluation outcome such as `UNRESOLVED` is a successful evaluation, not a claim that work was executed.

## Structured reports

The package exports `resolution_report`, `snapshot_audit_report`, and `work_decision_report`. Each emits a versioned JSON-compatible dictionary. Work reports preserve recommendation outcome separately from execution and persistence; audit reports preserve structural status separately from declared snapshot coverage. Serialization is local only and does not save or transmit the report.

## Run offline regression tests

From the repository root, install the package and run the suite:

    python -m pip install --no-deps ./runtime
    python -m unittest discover -s runtime/tests -v

The GitHub Actions workflow tests the installed package on the feature branch and pull requests to `main`. The live Airtable tests are skipped unless all three environment variables below are set.

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

## Durable retry-history boundary (verified against the pilot schema)

A fresh schema read of the pilot's `Records` table shows fields for record identity, project/branch identity, parent and fork lineage, sequence, event type, content, status, evidence references, revision target, open gaps, and last justified next action. It does **not** currently define dedicated structured fields for an operation fingerprint, prior retry fingerprints/attempt ledger, material-change evidence, or search-scope exhaustion.

Consequently, AF-AWR-001's fingerprint check is a caller-supplied guard, not durable deduplication across requests or chats. The existing pilot schema cannot independently establish that a retry was already attempted, that two requests describe the same operation, or that a search scope is truly exhausted. A new chat must therefore preserve the active project branch and checkpoint explicitly; a chat boundary alone is not evidence of a new project branch or a new operation.

No Airtable fields or records were added or changed during this inspection. Any future ledger design should first specify stable operation identity, attempt ordering, provenance, and idempotent write semantics, then be tested against an explicitly approved schema change.

## Attempt ledger contract (AF-LEDGER-001 draft)

`adaptive_filesystem_runtime.attempt_ledger` defines a pure, side-effect-free append contract for future persistence adapters. It distinguishes:

- **Operation identity:** stable `project_id + operation_id`, preserved across retries and chat transitions.
- **Attempt identity:** unique `attempt_id` and `idempotency_key`; an exact repeated append is an idempotent replay, while identifier reuse with different content is rejected.
- **Attempt order:** attempt numbers must be sequential within an operation; a different branch/checkpoint is provenance and does not reset the attempt number.
- **Changed inputs:** a later attempt may carry a different operation fingerprint, while retaining the same operation identity.

The contract is only a tested in-memory decision rule. It does not write a ledger, prevent races, prove evidence references, or provide durable deduplication. A real adapter must enforce the check-and-append atomically (for example with a conditional write or uniqueness constraints), and must preserve a stable operation ID across chats. The existing Airtable pilot schema has no dedicated ledger fields, so no Airtable integration or schema change is implied by this draft.

## SQLite persistence experiment (AF-LEDGER-002)

`adaptive_filesystem_runtime.sqlite_ledger.SQLiteAttemptLedger` is a local persistence prototype, not a production service. It uses a SQLite transaction plus uniqueness constraints for attempt IDs, idempotency keys, and (project, operation, attempt number). Tests exercise reopening the database, replay after restart, retry ordering across a branch/chat change, conflict rejection, two-instance concurrent appends, and recovery when a committed write's acknowledgement is lost.

The prototype's guarantee is limited to cooperating writes through this database file and its transaction/constraint behavior. It does not synchronize separate machines or databases, authenticate callers, validate evidence URIs, infer operation identity, or change the Airtable pilot schema. Stable `operation_id` remains a caller responsibility. Live Airtable behavior remains untested.

## Uncertain side-effect recovery (AF-RECOVERY-001 draft)

`adaptive_filesystem_runtime.recovery.decide_recovery` separates a durable attempt record from knowledge of the external outcome. If an external action may have started but there is no outcome evidence, recovery requires reconciliation unless the target itself supports the same stable idempotency key and the action is declared idempotent. Caller assertions alone do not establish target-side idempotency. Completion requires outcome evidence; contradictory or malformed facts fail closed.

This is a pure decision rule, not a worker or external-action executor. It does not perform reconciliation, prove evidence authenticity, or atomically coordinate an external service with the local ledger. The safe default for uncertain non-idempotent side effects is to stop and reconcile, not to repeat blindly.
