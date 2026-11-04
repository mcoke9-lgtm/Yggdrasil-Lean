# GitHub ref adapter assessment — 001

Status: **candidate assessed; no production adapter approved or implemented**.

## Scope

Evaluate GitHub Git references as the first concrete external-effect target for the Adaptive Filesystem orchestrator. This assessment is read-only. No branch ref was updated, created, deleted, force-pushed, or merged.

## Documented service behavior

GitHub's REST Git References API documents:

- `GET /repos/{owner}/{repo}/git/ref/{ref}` returns the current ref and target object SHA, or `404` when absent.
- `PATCH /repos/{owner}/{repo}/git/refs/{ref}` updates the ref to a supplied SHA. The documented request body includes `sha` and `force`; it does not document a caller-supplied idempotency key or an expected-current-SHA precondition.
- The documented REST update response is the new ref representation. A subsequent GET can inspect current state, but a later ref change can make that read insufficient to establish what happened at an earlier instant.

GitHub GraphQL documents `updateRefs` with `RefUpdate.beforeOid` and `afterOid`. It says all ref updates in that mutation are atomic and rejected if the before-OID precondition does not match. The GraphQL mutation inputs also expose `clientMutationId`; the docs describe it as a client mutation identifier, not as a durable idempotency guarantee or replay lookup facility.

Sources:
- https://docs.github.com/en/rest/git/refs
- https://docs.github.com/en/graphql/reference/git
- https://docs.github.com/en/rest/using-the-rest-api/best-practices-for-using-the-rest-api

## Assessment

| Capability | Evidence | Assessment |
|---|---|---|
| Read current branch target | REST Get a reference | Documented |
| Conditional compare-and-swap | GraphQL `updateRefs.beforeOid` | Documented atomic precondition |
| Caller idempotency key on REST ref update | Not in documented request schema | Not established |
| `clientMutationId` guarantees deduplication/replay | Docs call it a client mutation ID only | Not established |
| Read-back after lost acknowledgement | Get ref returns current target SHA | Useful, but only establishes current state |
| Prove historical mutation if ref subsequently moved | Current ref alone cannot prove this | Not established |

**Decision:** GitHub ref mutation is suitable for a carefully guarded compare-and-swap experiment, but the public documentation reviewed here does not establish the durable idempotency and historical receipt semantics required for a production exactly-once claim. Do not classify a matching current SHA as a signed historical receipt. Do not retry an ambiguous mutation merely because a read currently returns the old SHA: the first mutation may have succeeded and a later actor may have moved the ref again.

A robust adapter would need a stronger durable operation record, for example an independently queryable immutable operation marker linked to the exact target commit and authorization. The marker's uniqueness, authenticity, retention, and consistency guarantees would themselves need to be verified. GitHub's `clientMutationId` alone is not accepted as that record.

## Adapter contract if revisited

1. Use GraphQL `updateRefs` with an exact `beforeOid` and `afterOid`, never an unconditional force update.
2. Record the intended operation ID, exact ref name, before OID, after OID, authorization ID, and action fingerprint locally before mutation.
3. After timeout/lost acknowledgement, perform read-only lookup. If current ref equals after OID, record only **current target matches** unless independent evidence proves this operation caused it.
4. If current ref equals before OID, remain unresolved unless GitHub's consistency and concurrency contract is sufficient to establish safe retry.
5. If current ref equals neither, mark conflict/reconciliation required. Never overwrite the intervening update.
6. Keep transition authorization, environment permission, external mutation, observed current state, receipt verification, and local ledger commit as separate states.

## Repository read-only observation

At assessment time, `af-root-feedback-loop-redesign-v1` pointed to commit `2c875d14b6e3e77177cc83402b837d6a39069cb3`. This was a GET-only read; it did not alter the branch.

## Remaining gates

- No real GraphQL adapter or mutation test has been implemented.
- No live ref mutation was attempted or authorized.
- No durable operation-marker design has been verified.
- No receipt signature/issuer verification exists.
- A real service integration must first test documented precondition behavior, lookup consistency, races, permission scopes, and recovery after lost acknowledgements in a disposable test repository.
- Production adoption remains blocked pending the project's explicit authority and governance decisions.


## Refined design: atomic operation marker

The earlier "marker needed" gate has a concrete candidate design. GitHub GraphQL `updateRefs` accepts multiple `RefUpdate` entries and documents that all entries are applied atomically: if one is rejected, no other ref is modified. Each entry can use `beforeOid` as a precondition. A zero OID means the ref must not already exist.

Use one mutation containing exactly two ref updates:

1. **Target branch CAS:** `refs/heads/<target>`, `beforeOid=<captured old commit>`, `afterOid=<authorized target commit>`, `force=false`.
2. **Operation marker creation:** `refs/af-operations/<sha256(operation_id)>`, `beforeOid=000...000`, `afterOid=<marker commit>`, `force=false`.

The marker commit is prepared before the mutation. Its tree contains a canonical operation record binding:
- schema version and unique operation ID;
- repository and exact target ref;
- exact before and after OIDs;
- authorization ID and action fingerprint;
- approved scope;
- marker ref name.

The marker commit OID is recorded in the local intent before the mutation. The GraphQL `clientMutationId` is included only for request correlation; it is not treated as an idempotency key.

### Recovery rule

- Marker ref exists and points to the exact expected marker commit: **OPERATION_CONFIRMED**. Because marker creation and branch CAS were in the same documented atomic mutation, this confirms that the mutation succeeded, even if the target branch has subsequently moved.
- Marker ref exists but points elsewhere: **MARKER_CONFLICT**. Stop; do not overwrite.
- Marker lookup is unavailable or malformed: **RECONCILIATION_REQUIRED**.
- Marker ref is absent: absence alone is not success or proof of failure. The only permitted automated retry candidate is the exact same two-entry CAS mutation, with the same before/after OIDs and marker commit OID. Its branch precondition and marker-nonexistence precondition ensure that a duplicate cannot independently apply after the first atomic mutation. A failed retry must be followed by marker lookup; any unrelated branch movement remains a conflict, not permission to force-update.

This design closes the earlier gap where a later branch move erased the evidence needed to distinguish a prior success from a coincidental current SHA. It does not make GitHub's read APIs linearizable or establish universal exactly-once semantics.

### Mandatory trust and retention controls

- The operation-marker namespace must be protected against update and deletion by repository rules/permissions and a narrowly scoped service identity. This is a prerequisite, not yet verified for this repository.
- The marker record must be content-addressed and validated against the exact authorization and action fingerprint before accepting it.
- A marker ref's existence is evidence under the GitHub repository's access-control and integrity model; it is not a cryptographic signature by itself.
- The marker commit and ref must be retained for at least the authorization/audit retention period.
- The target branch update and marker creation are atomic only within this one GitHub GraphQL mutation. Local SQLite ledger commit is still a separate transaction and must be reconciled from the marker.
- If marker retention, immutability, permissions, or lookup behavior cannot be established, the adapter must fail closed and must not claim operation confirmation.

### Prototype artifacts

- `adaptive_filesystem/github_atomic_operation.py` builds the canonical marker record, deterministic marker ref, and two-entry GraphQL `updateRefs` input. It classifies marker lookup results.
- `tests/adaptive_filesystem/test_github_atomic_operation.py` defines twelve pure contract tests (GA-01 through GA-12).

These are local helper and model-contract tests only. No GraphQL mutation was issued, no marker ref was created, no branch ref was changed, and no live service behavior was tested.

### Revised decision

**Proposed design, not adopted:** GitHub's documented atomic multi-ref mutation gives a credible route to a durable operation marker. Proceed next with a disposable-repository integration design and a read-only verification of the repository ruleset/permissions that would protect `refs/af-operations/*`. Do not run the mutation until the user explicitly authorizes a named disposable target and the marker protection controls are verified.
