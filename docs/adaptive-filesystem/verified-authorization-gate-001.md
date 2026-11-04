# Verified Authorization Gate — FIRA Checkpoint

**Status:** Implemented as a narrow adapter; contract tests committed.  
**Authority status:** The verifier is injected and trusted by this adapter. No verifier implementation or governance policy is adopted here.  
**Effect boundary:** Registration only. The gate does not perform a branch transition or any external action.

## Request-to-ledger path

1. The caller supplies an authorization ID, actor, source branch and checkpoint, target branch, and a non-empty list of scoped actions.
2. The gate derives the action fingerprint locally from those action fields.
3. The caller-supplied fingerprint must exactly match the derived value; otherwise the request is rejected before the verifier is called.
4. The injected verifier evaluates the request against its own authoritative policy and evidence sources.
5. The gate accepts only a mapping with `status="VERIFIED"` and exact matching authorization ID and derived fingerprint.
6. Only then does it register that ID/fingerprint pair with the execution ledger.

Missing or malformed fields, forged fingerprints, verifier exceptions, denial, malformed responses, identity/scope mismatch, duplicate IDs, and ledger failures all reject.

The gate returns `REGISTERED`, not `EXECUTED`, `COMMITTED`, or `TRANSITIONED`. A later controller must separately check environment-action permission, reserve/consume the ledger authorization, perform an idempotent or reconcilable effect, and record evidence of the actual effect.

## Fingerprint profile

The current `af-action-v1` profile hashes a constrained JSON object containing actor, source branch, source checkpoint, target branch, and scope. Scope is a set of unique non-empty strings and is sorted before serialization. JSON keys are sorted, separators are fixed, UTF-8 is used, and SHA-256 produces the digest.

This is a deliberately constrained local canonicalization profile, **not an implementation of RFC 8785/JCS**. It must be versioned and treated as an internal prototype contract. Cross-language interoperability requires a separately specified and tested canonicalization standard before production use.

## Trust boundary

The injected verifier is the trust root. The gate does not authenticate the verifier, validate signatures, establish issuer authority, retrieve evidence, determine whether the verifier's policy is legitimate, or prevent a compromised caller from injecting an untrustworthy verifier. The adapter's tests use a fake verifier and fake ledger; they test the interface contract, not the trustworthiness of either dependency.

## Tests

Eleven VG tests cover verifier-gate behavior and six AF tests cover deterministic fingerprinting, scope normalization, malformed scope rejection, required fields, and digest format.

Run the full directory with:

```sh
python -m unittest discover -s tests/adaptive_filesystem -p 'test_*.py' -v
```

## Next gate

Before this can be connected to real execution, select and review a real authority verifier, define the canonical authorization schema across platforms, add an independent environment-permission check, and integrate effect receipts/reconciliation. A verifier's `VERIFIED` string is not cryptographic proof by itself. No branch transition, shared write, or governance adoption is authorized by this prototype.


## Independent environment permission gate

A separate `EnvironmentPermissionGate` now asks an independently injected provider whether a specific actor may perform a named action in a named environment. Its response must be `PERMITTED` and exactly match the authorization ID, action fingerprint, actor, environment ID, and environment action. Missing fields, denial, provider exceptions, malformed responses, and mismatches reject.

`ExecutionReadinessGate` requires both:
- transition authorization registered by `VerifiedAuthorizationGate`; and
- independent environment permission returned by `EnvironmentPermissionGate`.

It also requires the authorization ID and action fingerprint to match across both decisions. Its `READY` result explicitly reports `effect_executed=False`. It does not call the ledger's commit method or perform an external action. A registered transition authorization can remain unused if environment permission is denied; this is safe but requires a later expiry/revocation policy.

The environment provider is another injected trust boundary, not an adopted policy implementation. These tests use fakes and do not prove real-world authority, environment identity, or effect execution.

Twelve EP/ER tests cover exact permission, missing bindings, denial, provider errors, environment/action mismatches, malformed responses, both-gates-required, transition rejection, binding mismatch, and the no-effect boundary.
