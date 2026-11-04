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
