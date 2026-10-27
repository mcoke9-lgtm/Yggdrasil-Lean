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

After installing the package, `af-runtime` (or `python -m adaptive_filesystem_runtime`) accepts JSON on standard input and writes versioned JSON reports to standard output. It supports `audit`, `resolve`, `evaluate`, and `recover-evaluate`; it never contacts Airtable or persists decisions. `recover-evaluate` accepts both a work `request` and `recovery_facts`, then emits a versioned combined report whose retry gate is distinct from execution and persistence status.

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

## Retry/recovery integration gate (AF-RECOVERY-002 draft)

`adaptive_filesystem_runtime.recovery_gate.gate_retry` combines the existing alternative-work recommendation with the recovery decision. A `RETRY_JUSTIFIED` recommendation is not sufficient by itself: retries are allowed only when recovery returns `SAFE_TO_START` or `SAFE_TO_REPLAY`. Unknown side effects, confirmed completion, and conflicting recovery facts block the retry. This is a decision gate only; it does not execute or persist the retry.

## End-to-end recovery reporting (AF-RECOVERY-003 draft)

The `recover-evaluate` CLI command evaluates the work request, decides recovery from supplied facts, and emits `af.recovery-gated-work.v1`. Tests confirm an otherwise justified retry remains blocked when an external side effect is uncertain, including across separate fresh CLI invocations with the same input. The report explicitly keeps `retry_allowed`, `execution_status`, and `persistence_status` separate. Repeating a deterministic evaluation is not durable state or proof that external facts remained unchanged; no operation is executed.

## Ledger-anchored recovery (AF-RECOVERY-004 draft)

`adaptive_filesystem_runtime.ledger_recovery.decide_recovery_with_ledger` requires recovery facts to identify an attempt present in the SQLite ledger for the supplied project and operation, and requires it to be the latest attempt for that operation. Missing, cross-project, or stale attempt references fail closed. The ledger anchors attempt identity and sequence; it does not authenticate external evidence or independently prove the real-world outcome. Tests cover restart, missing identity, wrong project, stale attempts, and conflicting recovery facts. This remains a local SQLite prototype.

## Recovery decision freshness (AF-RECOVERY-005 draft)

`check_recovery_decision_freshness` re-reads the local ledger and detects when a decision names an attempt that is no longer the latest. Tests cover a current decision, a decision made stale by an append through a second ledger instance, and a missing attempt after database separation. This is a point-in-time freshness check only: another append can occur immediately after the check. It is **not** an atomic authorization for external effects. A safe external executor would need a durable claim/lease protocol and target-enforced idempotency or reconciliation; neither is implemented here. No Airtable writes or schema changes are involved.


## Recovery coordination lease (AF-RECOVERY-006 draft)

`SQLiteAttemptLedger.claim_recovery_review` atomically checks the latest persisted attempt and acquires a short-lived, per-operation coordination lease in the same SQLite transaction. A competing worker cannot acquire the same unexpired lease; the exact same claim is reported as an idempotent replay. Expired leases can be reclaimed, and release requires matching claim and owner identifiers. Tests cover persistence after reopen, competing workers, expiry/reclaim, stale and missing attempts, exact-owner release, and a two-instance race.

The lease coordinates cooperating workers using the same local database. It is **not** permission to repeat an external action, does not prove the prior effect's outcome, and does not make a remote side effect atomic with the SQLite transaction. Wall-clock lease expiry assumes reasonably consistent system time. A production design still needs authenticated worker identity, careful lease renewal/fencing, and target-enforced idempotency or reconciliation. This remains a local prototype; no Airtable schema or records were changed.


## Recovery fencing generation (AF-RECOVERY-007 draft)

The SQLite recovery lease now carries a monotonically increasing fencing token per operation. A fresh claim starts at token 1; each successful claim after expiry increments the stored generation. `is_recovery_claim_current` checks claim identity, owner, token, lease expiry, and whether the claimed attempt is still the latest ledger attempt. Release also requires the fencing token, so an old claim cannot release a newer generation. Non-finite lease durations and timestamps are rejected.

This is a local, point-in-time check, **not** remote fencing: a paused worker can pass a check, pause, then resume after a replacement claim. Preventing stale external effects requires the downstream target to persist and reject older fencing tokens (or equivalent target-side idempotency), plus careful authorization and reconciliation. The local token alone cannot fence a remote service and is not permission to execute or replay an action. Tests cover expired-claim replacement, stale-token validation/release, and existing lease lifecycle behavior.


## Target-enforced fencing contract (AF-RECOVERY-008 reference model)

`adaptive_filesystem_runtime.target_fencing.InMemoryFencedTarget` is an in-memory test model of a target service. Under one atomic lock it remembers the highest fencing token per operation, rejects lower tokens, and deduplicates exact idempotency-key/payload replays while rejecting changed payloads under a reused key. Its tests model an old worker attempting an effect after a newer generation has reached the target.

This demonstrates the contract a real target must implement, not a remote integration or proof of production safety. A target must durably and atomically compare/update the highest token **in the same transaction as the protected effect**. If token 1's request reaches the target before token 2, the target may accept it; fencing only rejects old requests after the newer generation has reached and been recorded by that target. Targets that cannot enforce this need a supported idempotency/reconciliation strategy. The model has in-memory state only and loses it on restart; it is not a durable target implementation.

## Evidence provenance gate (AF-RECOVERY-010 reference policy)

`adaptive_filesystem_runtime.evidence_provenance.decide_recovery_with_provenance` layers provenance checks over the existing recovery decision without claiming to verify evidence by itself. It distinguishes absent, caller-asserted, verified, and invalid evidence; requires a verifier identity and separate verification-record reference for verified evidence; and requires the verified outcome reference to match the exact outcome reference in the recovery facts. A caller assertion alone cannot confirm completion. Invalid or inconsistent provenance fails closed.

The verifier identity and verification-record reference are still supplied inputs. This module does **not** authenticate the verifier, retrieve the referenced evidence, validate the authority of the source, or cryptographically prove integrity. A production adapter must perform those checks against an authoritative source and record the result durably. Tests exercise policy behavior only; no external evidence source is contacted.


## Authoritative evidence adapter boundary (AF-RECOVERY-011 contract)

`adaptive_filesystem_runtime.evidence_adapter` defines a replaceable protocol for checking a requested evidence reference and claim, plus a result validator that rejects mismatched references/claims and incomplete `VERIFIED` metadata. Outcomes are explicit: `VERIFIED`, `INVALID`, or `UNAVAILABLE`; unavailable is not success. The accompanying fake-adapter tests exercise the contract without network access.

This is an interface and consistency guard, **not a live authoritative-source integration**. The validator cannot authenticate an adapter or verifier, establish source authority, fetch evidence, or prove cryptographic integrity. A production implementation must independently authorize and query the source, validate its response, and durably record the verification. No external evidence is contacted by these tests.
