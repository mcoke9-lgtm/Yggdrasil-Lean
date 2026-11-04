# Execution Ledger Prototype — FIRA Checkpoint

**Status:** Implemented as a reusable SQLite component; CI-tested on the redesign branch.  
**Authority status:** Prototype only. This document does not adopt a governance policy or authorize a branch transition.  
**Scope:** One SQLite database, one authorization identifier, one atomic ledger outcome.

## Purpose

The execution ledger closes a narrow gap between a scoped authorization and the durable record that it has been consumed. It is intended to prevent two competing attempts from both consuming one authorization, and to make a retry after a lost acknowledgement return the original recorded outcome.

The component lives at `adaptive_filesystem/execution_ledger.py`. Its contract tests live at `tests/adaptive_filesystem/test_execution_ledger_contract.py`.

## Contract

1. An upstream authority layer must verify the authorization before calling `register_verified_authorization`. The method name records that precondition; the ledger itself does not verify signatures, issuer authority, delegation, expiry, or evidence.
2. Each authorization ID is unique and bound to one action fingerprint.
3. `commit_once` takes a SQLite `BEGIN IMMEDIATE` transaction, checks the stored authorization and action fingerprint, and changes `authorized` to `committed` while storing the outcome in the same transaction.
4. A repeat with the same authorization and action fingerprint returns `REPLAY` with the original outcome. It does not replace that outcome with the retry payload.
5. Unknown authorizations, revoked authorizations, and action-fingerprint mismatches fail closed.
6. Revocation and commit serialize through SQLite's write transaction. If revocation wins, commit is rejected; if commit wins, later revocation cannot rewrite the committed record.
7. `inspect` is diagnostic and read-only. It does not confer permission.

## Failure and retry meaning

- **Process stops before transaction commit:** SQLite rolls back the uncommitted write; the authorization remains available for a later attempt.
- **Process stops after commit but before acknowledgement:** a new process can reopen the database and retrieve the original committed outcome as a replay.
- **Two simultaneous commit attempts:** one commits; the other receives the already-committed outcome.
- **External side effect:** not made atomic by this ledger. A caller that performs an external action must make that action idempotent or reconcile its state against the durable ledger outcome.

## What this prototype does not establish

- Cross-platform or multi-database consensus.
- Exactly-once execution of external actions.
- Signature validation or authority-policy enforcement.
- Atomic coupling between a Git branch mutation, a remote API call, or another external effect and the SQLite commit.
- Production database deployment, backup/restore, migration, key management, monitoring, or operational recovery.
- Any governance decision about who may issue, approve, revoke, or delegate authorizations.

SQLite transactions establish atomicity only within this database. A disconnected platform may continue independent local work, but it must not infer permission to commit a shared transition from a stale local copy.

## Verification

The CI harness runs the complete Python contract-test directory on pushes to `af-root-feedback-loop-redesign-v1`. The ledger tests cover simultaneous claims, lost acknowledgements, actual child-process termination before and after commit, revocation/commit ordering, action-scope mismatch, unknown authorization, and read-only inspection.

A successful test run verifies the prototype's behavior in the CI environment. It does not verify a production controller or distributed deployment.

## Next integration gates

1. Define and implement the authority verifier that supplies trusted, scoped authorization records.
2. Connect the ledger to an execution controller without allowing FIRA to approve on behalf of affected branches.
3. Define the external-effect protocol: idempotency key, effect receipt, reconciliation record, and behavior when the effect succeeds but the caller loses its acknowledgement.
4. Add multi-process and multi-platform integration tests against the actual persistence service selected for deployment.
5. Establish backup/restore, schema migration, observability, and failure recovery requirements.
6. Obtain explicit governance decisions before enabling real branch transitions or shared writes.

Until those gates are met, this component is a verified single-database prototype, not an operational cross-platform authorization system.


## External-effect receipt and reconciliation prototype

The separate `adaptive_filesystem/effect_receipts.py` component introduces a durable protocol for the gap between readiness and a remote side effect:

1. Persist an `INTENDED` record with a stable idempotency key and exact authorization/action/environment binding **before** calling the external executor.
2. Send that same idempotency key to the external system. The external system must enforce idempotency or expose an authoritative lookup; this local SQLite store cannot enforce remote deduplication.
3. Persist a returned receipt only when its authorization ID, action fingerprint, environment ID, and environment action match the intent. The receipt ID and canonical outcome digest are retained.
4. If the caller cannot determine whether the remote action happened, mark `RECONCILIATION_REQUIRED`. Query the remote system by idempotency key and then record its authoritative receipt, or leave the item unresolved. Do not blindly retry a non-idempotent action.

The store rejects reuse of an idempotency key with a different binding, unknown intents, mismatched receipts, and conflicting second receipts. Identical intent and receipt retries are idempotent. The receipt outcome is represented by a SHA-256 digest of deterministic JSON; the full outcome is not stored.

### State meaning

- `INTENDED`: local intent is durable; remote effect may not have been attempted yet.
- `RECONCILIATION_REQUIRED`: remote outcome is uncertain. This is not success or failure.
- `RECEIVED`: a matching receipt has been recorded. This is evidence returned by the configured external system, not independent proof that the external system is truthful.

Absence of a local receipt does **not** prove that the remote effect did not happen. A recorded receipt does not make the remote effect atomic with the local ledger. This component does not itself call the external system, execute effects, validate receipt signatures, query remote state, or automatically retry.

The twelve effect-receipt contract tests cover intent persistence/replay, idempotency-key binding conflicts, unknown/mismatched receipts, matching receipt persistence, duplicate/conflicting receipt behavior, uncertain outcomes, resolution, read-only inspection, and stable outcome digests.

### Remaining integration gates

- Select a real external system and determine whether it supports idempotency keys and authoritative operation lookup.
- Define and verify receipt authenticity, issuer identity, timestamps, and retention.
- Add an orchestrator that sequences readiness → durable intent → remote request → receipt or reconciliation, with explicit crash recovery.
- Decide how the execution ledger's `commit_once` relates to the external receipt. Do not report a branch transition as committed merely because readiness or intent exists.
- Test against the selected real service, including remote success with lost acknowledgement and remote failure before effect.


## Crash-aware orchestrator

`adaptive_filesystem/external_effect_orchestrator.py` now composes the existing readiness gate, receipt store, external executor interface, and execution ledger. It is an injected-interface prototype; it does not connect to a real GitHub or other remote executor.

### Normal path

1. Require `ExecutionReadinessGate` to return `READY`.
2. Persist the idempotency key and exact authorization/action/environment binding as `INTENDED`.
3. Call the external executor once, passing the same idempotency key.
4. Require the executor's receipt to echo the exact authorization ID, action fingerprint, environment ID, and environment action.
5. Persist the receipt and outcome digest.
6. Only after receipt persistence, call the local ledger's `commit_once` with a durable reference to that receipt.

### Crash and recovery path

| Failure point | Durable local state | Recovery behavior |
|---|---|---|
| Before intent commit | No intent | No remote call should have been made |
| After intent, before remote call | `INTENDED` | Lookup remote state; do not assume no effect |
| Remote effect succeeds, acknowledgement lost | `RECONCILIATION_REQUIRED` | Lookup by same idempotency key; if found, verify binding, store receipt, commit ledger |
| Remote outcome cannot be located | `RECONCILIATION_REQUIRED` | Remain unresolved; do not blindly re-execute |
| Receipt stored, ledger commit fails or acknowledgement lost | `RECEIVED` | Reconcile locally from stored receipt and retry `commit_once`; do not call remote executor |
| Ledger committed, response lost | `RECEIVED` plus committed ledger | Replay `commit_once` and retrieve original outcome |

Recovery's `reconcile` method is lookup-only. It never calls the remote execute method. A `NOT_FOUND` lookup remains unresolved because remote lookup consistency and idempotency guarantees depend on the external service. A future integration may add controlled retry only after the selected service's contract proves that retrying the same idempotency key is safe.

The orchestrator rejects a receipt whose echoed identity/action/environment bindings differ from the durable intent. It distinguishes `effect_executed=True` on the initial successful call from `False` when recovering a remote effect or replaying a stored receipt.

### Orchestrator tests

Nine `OE-01` through `OE-09` tests cover the normal path, remote success with lost acknowledgement, unresolved lookup without re-execution, receipt-persisted/ledger-failed recovery, receipt replay, readiness denial, unknown intent, idempotency-key binding conflict, and foreign receipt identity rejection. Tests use fake gates/executors and local SQLite databases; they do not verify a real remote service or receipt signature.

### Remaining limitations

- The external executor must provide durable idempotency-key semantics and an authoritative lookup API. This prototype cannot create those guarantees for a remote system.
- Receipt identity fields are matched, but receipt signatures/issuer authenticity are not verified.
- There is no real-service adapter, remote integration test, distributed consensus, or automatic recovery worker.
- A locally committed ledger outcome is a record that a matching receipt was observed; it does not make a remote side effect and SQLite commit atomic.
- Real branch mutation remains disabled pending authority, policy, remote API, recovery, and explicit governance gates.
