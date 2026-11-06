# Builder Run Ledger v2 — Design Proposal

**Status:** Proposed; not implemented or tested  
**Design baseline:** `7ca8538273ecdd5b45cd03d8760b22605e4d6499`  
**Scope:** Plaintext Builder-run ledger, Builder-to-Builder handoff, recovery, and process output

## 1. Purpose and non-goals

The v1 Builder-run ledger is useful for scheduling, recovery, and review continuity, but it stores arbitrary summaries, exception messages, interruption reasons, and checkpoint JSON in a plaintext JSON file. Owner-only filesystem permissions reduce local exposure; they are not encryption or content validation.

Version 2 should make the plaintext ledger a metadata index only. Full task prompts, Builder turn bodies, evidence records, and any diagnostic text that is deliberately retained belong in the encrypted task vault. This proposal does not encrypt the scheduler state file, add a secret manager, create a remote dashboard, or claim that already-written v1 text or previously emitted logs can be made confidential retroactively.

## 2. Proposed v2 run record

A v2 run retains only fields needed to schedule, sequence, recover, and audit work:

- `schemaVersion: 2`
- stable `runId`
- opaque `taskReference` identifying a task-vault entry (new v2 references must be non-semantic identifiers; do not use prompts, names, email addresses, or descriptive text)
- `scheduledFor`, `createdAt`, `updatedAt`, `startedAt`, `finishedAt`, `deadlineAt`, `durationMs`
- typed run `status`
- typed `interruptionCode` (nullable), never a free-text interruption reason
- optional `supersedesRunId` for an explicitly reconciled v1-to-v2 continuation
- exactly nine role records in canonical order

Each role record contains:

- `role`
- typed `status`: `pending | running | succeeded | failed | skipped | interrupted`
- `attempt`
- `startedAt`, `finishedAt`
- nullable `errorCode` from the allowlist in section 4
- nullable `outcomeRef`: opaque encrypted-vault turn/checkpoint identifier
- nullable `outcomeType`: protocol turn type from the existing supported turn-type set
- nullable `evidenceStatus`: existing typed evidence-status enum
- nullable `checkpoint`, containing only the allowlisted fields in section 3

No free-text `summary`, `error`, `reason`, prompt, response body, endpoint, credential, or arbitrary object is permitted in a v2 plaintext record. Unknown fields must be rejected, not silently copied.

## 3. Checkpoint metadata allowlist

The v2 checkpoint object is deliberately narrow and identical for each of the nine roles until a demonstrated role-specific requirement is approved:

- `checkpointId`: stable opaque vault checkpoint identifier
- `vaultTurnId`: stable turn identifier; may equal `checkpointId` where the vault contract says they identify the same record
- `responseSha256`: optional 64-character lowercase SHA-256 integrity reference
- `turnType`: optional supported protocol turn type
- `safeToResume`: boolean explicitly asserted by the executor
- `recordedAt`: canonical ISO-8601 timestamp

No role may add arbitrary checkpoint data. If a future role needs extra metadata, add a versioned, role-specific schema with explicit field names, types, size limits, and tests. A hash is an integrity/correlation value, not encryption or proof that the underlying content is non-sensitive.

## 4. Error and interruption codes

The ledger stores codes, never exception messages. Initial proposed error codes:

- `EXECUTOR_UNAVAILABLE`
- `EXECUTOR_FAILURE`
- `CONSENT_REQUIRED`
- `CONSENT_STALE`
- `TASK_VAULT_UNAVAILABLE`
- `VAULT_RESULT_MISSING`
- `VAULT_IDEMPOTENCY_CONFLICT`
- `PROVIDER_TIMEOUT`
- `PROVIDER_REJECTED`
- `PROVIDER_INVALID_RESPONSE`
- `EVIDENCE_UNAVAILABLE`
- `EVIDENCE_MISMATCH`
- `CHECKPOINT_INVALID`
- `RESUME_NOT_SAFE`
- `MANUAL_RECONCILIATION_REQUIRED`
- `CANCELLED`
- `DEADLINE_EXPIRED`
- `HOST_INTERRUPTED`
- `EXTERNAL_OUTCOME_UNKNOWN`
- `INTERNAL_ERROR`

These are a proposal, not a final taxonomy. Internal code should raise typed errors with allowlisted codes. Unknown host/provider exceptions must collapse to `EXECUTOR_FAILURE` or `INTERNAL_ERROR`; do not classify by copying arbitrary message text. If detailed diagnostics are ever needed, add a separate encrypted-vault diagnostic record with a typed schema and explicit retention policy. Do not create one by blindly storing `error.message`.

## 5. Preserve Builder handoff and Baldr review

The encrypted task vault already persists the full validated run-thread history, including Builder turn bodies, turn IDs, protocol types, evidence statuses, provider/model metadata, response hashes, and evidence-verification records. The executor's `getTask(taskReference, { runId })` returns this history to the trusted executor.

The v2 `context.outcomes` passed between roles and to Baldr should therefore contain metadata only:

```json
{
  "role": "HUGINN",
  "status": "succeeded",
  "attempt": 1,
  "outcomeRef": "vault-turn-reference",
  "outcomeType": "CONTRIBUTION",
  "evidenceStatus": "OBSERVED",
  "errorCode": null
}
```

The model request can include the full prior turn bodies from the encrypted vault history, as it does today, while the outcome array tells Baldr which roles succeeded, failed, or were skipped and points to their durable records. Baldr's existing completion gate must remain: all eight peer roles must have a terminal outcome, and every successful peer must have its corresponding turn in the same run-specific vault thread. Missing or mismatched turns must stop review for reconciliation; never replace missing evidence with generic summaries.

A failed role may have no vault turn. In that case, Baldr receives the role status and typed error code only. If the failure requires more context, that context must be separately and deliberately stored in the encrypted vault; it must not be reconstructed from plaintext v1 errors.

## 6. Output and logging contract

The Builder handler's stdout events must be metadata-only. Completion and interruption events may contain event name, run ID, role, status, turn type, error/interruption code, and timestamp. They must not contain summaries, exception messages, prompt fragments, response text, or free-text reasons.

The Builder handler must also throw only a stable, sanitized error code/message to the outer scheduler. The general scheduler currently persists `error.message` in its own plaintext run record and exposes that value through dashboard and CLI. Builder-specific exceptions must not carry arbitrary provider or host text across this boundary. General non-Builder scheduler handlers require their own separate error-handling review; this proposal does not silently change their contract.

### Required encrypted-vault history fork for a distinct v2 continuation

The current vault keys run history by `runId` and validates that every turn belongs to that run's dedicated thread. Therefore a new v2 ledger run cannot simply point at a v1 run's thread and pass that history to the current executor: the run/thread identity checks and append idempotency key would no longer agree.

A distinct v2 continuation consequently requires a vault-side, encrypted, atomic history-fork operation before it can safely start:

- The operation takes the task reference, source v1 run ID, new v2 run ID, expected source thread ID, and an expected source-history digest or equivalent compare-and-swap token.
- It verifies the source thread and history, then creates the target run thread with the source turns copied in order. Each imported turn receives the target run/thread identity while retaining its original turn ID, body, protocol metadata, evidence verification, provider/model metadata, response hash, and explicit origin fields (`sourceRunId`, `sourceThreadId`, `sourceTurnId`). This preserves source provenance without mixing run identities.
- The operation is idempotent for the same target run ID and source manifest; conflicting reuse fails closed. It never mutates or deletes the source v1 thread. Imported turns must retain their original turn IDs and reply links so protocol history remains intact. In particular, preserve any stored `userAuthorization` record and its original task/run/thread binding verbatim; do not rewrite or re-sign it for the target thread. Important distinction: the current vault stores the normalized, verified authorization record (including `signatureSha256` and source identities), not the original raw Ed25519 signature. The record was checked for signature validity and freshness before it was encrypted and appended; the encrypted vault authenticates its stored bytes. A fork must preserve that record exactly and must not claim it has re-verified the original signature. The import envelope must record both source and target identities so the provenance of a historical user action remains explicit. Extend history validation to accept this explicit imported-record shape and require the stored authorization's taskReference/runId/threadId to match the import's source identity, while the enclosing turn belongs to the target thread. Preserve the task-level prompt turn ID and source timestamp unchanged across the fork.
- New v2 Builder turns append only to the target thread and use the new run's idempotency keys. The encrypted vault remains the source of full content; the plaintext v2 ledger stores only target/source references and typed outcomes.
- Before seeding v2 role outcomes, reconcile each v1 `succeeded` role against exactly one source-thread turn matching its saved checkpoint/turn ID and role. Missing, duplicate, or mismatched turns block that role and Baldr pending manual reconciliation. Do not infer success from a v1 summary.
- V1 failed/interrupted/pending roles do not inherit raw error text or automatic resume permission. Their status is reconciled from the source vault and operator decision; otherwise record `MANUAL_RECONCILIATION_REQUIRED`. A v1 `safeToResume` flag alone is not sufficient to bypass the new validation.
- Record the source run ID and source history manifest on the v2 run. The original v1 ledger record and vault thread remain unchanged.

This fork is a new required vault capability, not an existing feature. Until it is implemented and tested, the design's distinct-run continuation path is incomplete and v1 runs must remain read-only/manual-reconciliation cases.

#### Proposed imported-turn envelope and atomicity

For each copied turn, retain its original `turnId`, `checkpointId`, body, reply target, timestamps, protocol fields, evidence proof, provider/model fields, response hash, and idempotency key. Rebind only the enclosing turn's `runId` and `threadId` to the target run/thread, and add a reserved import-origin object:

```json
{
  "origin": {
    "kind": "ENCRYPTED_VAULT_RUN_FORK",
    "sourceRunId": "legacy-run-id",
    "sourceThreadId": "nine-builders:legacy-run-id",
    "sourceTurnId": "original-turn-id"
  }
}
```

The task prompt turn ID and task prompt creation timestamp remain task-scoped and unchanged. The fork must preserve turn ordering and all reply links; retaining the original turn IDs avoids rewriting the directed conversation graph. For a copied USER adoption turn, the normalized `userAuthorization` record remains source-bound exactly as stored. The history validator must require that its taskReference, runId, and threadId match the fork's source identity, while the enclosing turn's runId/threadId match the target. This is a narrowly typed exception, not permission for arbitrary cross-run history.

The fork API should execute inside the vault's existing cross-process mutation lock and one atomic encrypted-file replacement. Suggested contract: `forkRunHistory(taskReference, { sourceRunId, targetRunId, expectedSourceThreadId, expectedSourceHistorySha256 })`. It validates the source history before cloning, computes a canonical source-history digest while holding the lock, and rejects if the caller's expected digest differs. A repeated request for the same target and same source digest returns the existing fork result; target-ID reuse with a different source or digest fails closed. The target thread is created only once, and the source thread is never modified. The digest is a compare-and-swap integrity token, not a privacy mechanism or evidence of content safety; it should remain inside the trusted host/vault handoff and never be emitted to logs or the plaintext ledger.

This contract also requires tests for same-task binding, source-history mutation between read and fork, concurrent competing forks, identical idempotent retry, conflicting target reuse, preservation of turn order/reply links, signed-adoption source binding, and target-thread append after import.


## 7. v1 compatibility and migration

- Treat each existing v1 run as immutable legacy data. A schema upgrade must not rewrite, truncate, delete, or silently reinterpret its free-text fields.
- Readers must identify v1 explicitly and label it as legacy plaintext. Read-only display may continue, subject to existing access controls.
- Do not automatically copy v1 summaries, errors, or interruption reasons into v2.
- A v1 continuation should create a distinct v2 run with `supersedesRunId`, after an operator-controlled reconciliation step. Preserve the original v1 record as the historical source.
- Reconcile successful v1 roles against the exact run/thread in the encrypted vault using their saved checkpoint/turn identifiers. Only a matching durable vault turn can become a v2 `outcomeRef`. A v1 status of succeeded without a matching vault turn is not sufficient proof of a recoverable result.
- Do not carry v1 failure text forward. Map only facts that can be independently established; otherwise use `MANUAL_RECONCILIATION_REQUIRED`.
- Require an operator-controlled backup and secure archive decision before any optional removal of legacy plaintext. Do not claim that an archive is secure unless its encryption, access controls, and restore path have been verified.
- New runs use v2 only after the reader, writer, resume path, dashboard/log contract, and Baldr continuity tests are all in place. Avoid mixed-version mutation of a single run.

## 8. Required tests before implementation is considered verified

1. Strict v2 schema rejects unknown fields, free text, oversized values, invalid role order, and invalid timestamps.
2. v1 records remain byte/field-equivalent through read-only access; no automatic migration or data loss.
3. A v1-to-v2 continuation preserves the original record and requires explicit reconciliation.
4. Successful role references resolve to the exact run/thread vault turn; missing or duplicate turns block Baldr.
5. Baldr receives all eight peer statuses and the full successful turn history, without depending on plaintext summaries.
6. Failed roles without vault turns are represented only by typed codes; no raw exception text is persisted.
7. Completion/interruption stdout events contain no free text; the scheduler's stored Builder error is a safe code.
8. Cancellation, deadline expiry, durable-result recovery, idempotency conflicts, and non-cooperative handlers preserve existing lock and no-replay guarantees.
9. Mixed v1/v2 state is read safely, and a v2 run cannot accidentally resume or mutate a v1 record.
10. Tests use fake providers and vaults and are described as contract tests, not live provider execution.

## 9. Decision gate

This document is a design proposal only. No runtime behavior changes here. Implementation should begin only after the exact v2 schema, code taxonomy, vault reference lookup contract, and v1 continuation procedure have been reviewed against existing tests and host integration requirements. Baldr review remains a separate invocation and must not be simulated by this document or by the ordinary executor sequence.
