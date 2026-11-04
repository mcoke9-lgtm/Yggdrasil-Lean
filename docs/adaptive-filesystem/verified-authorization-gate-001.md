# Verified Authorization Gate — FIRA Checkpoint

**Status:** Implemented as a narrow adapter; contract tests committed.  
**Authority status:** The verifier is injected and trusted by this adapter. No verifier implementation or governance policy is adopted here.  
**Effect boundary:** Registration only. The gate does not perform a branch transition or any external action.

## Request-to-ledger path

1. The caller supplies a request containing an authorization ID, action fingerprint, actor, source branch and checkpoint, target branch, and scope.
2. The injected verifier evaluates the request against its own authoritative policy and evidence sources.
3. The gate accepts only a mapping with `status="VERIFIED"` and exact matching authorization ID and action fingerprint.
4. Only then does it register that ID/fingerprint pair with the execution ledger.
5. Missing fields, verifier exceptions, denial, malformed responses, identity/scope mismatch, duplicate IDs, and ledger failures all reject.

The gate returns `REGISTERED`, not `EXECUTED`, `COMMITTED`, or `TRANSITIONED`. A later controller must separately check environment-action permission, reserve/consume the ledger authorization, perform an idempotent or reconcilable effect, and record evidence of the actual effect.

## Trust boundary

The injected verifier is the trust root. The gate does not authenticate the verifier, validate signatures, establish issuer authority, retrieve evidence, determine whether the verifier's policy is legitimate, or prevent a compromised caller from injecting an untrustworthy verifier. The adapter's tests use a fake verifier and fake ledger; they test the interface contract, not the trustworthiness of either dependency.

## Tests

The nine VG tests cover exact matching registration, missing request fields, non-verified decisions, authorization-ID mismatch, action-fingerprint mismatch, verifier exceptions, malformed verifier responses, ledger registration failures, and the no-external-effect boundary.

Run the full directory with:

```sh
python -m unittest discover -s tests/adaptive_filesystem -p 'test_*.py' -v
```

## Next gate

Before this can be connected to real execution, select and review a real authority verifier, define the canonical authorization schema and action-fingerprint construction, add an independent environment-permission check, and integrate effect receipts/reconciliation. A verifier's `VERIFIED` string is not cryptographic proof by itself. No branch transition, shared write, or governance adoption is authorized by this prototype.
