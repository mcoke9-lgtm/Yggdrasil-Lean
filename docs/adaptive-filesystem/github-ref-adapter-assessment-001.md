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


### Repository protection inspection (read-only, 2026-11-04)

- Repository-level `GET /repos/{owner}/{repo}/rulesets` returned an empty list.
- The branch-protection endpoint returned HTTP 403 (`Resource not accessible by integration`).
- Organization-level ruleset coverage and any policy that would protect `refs/af-operations/*` are therefore **Unknown**.
- An empty repository-level ruleset list is not evidence that marker refs are protected. No marker namespace should be used for a live operation until an authorized administrator verifies effective rules and deletion/update permissions.


## Correction: operation markers must use protected tag refs

A further review of GitHub's ruleset documentation exposed a design flaw in the initial marker namespace. Repository rulesets are documented as targeting **branches and tags**; they do not provide a documented protection target for an arbitrary custom ref namespace such as `refs/af-operations/*`. The earlier custom-ref proposal therefore did not establish the required protection and is superseded.

The helper now places each operation marker under:

`refs/tags/af-operations/<sha256(operation_id)>`

This makes a tag ruleset a documented protection mechanism. The intended administrator configuration is a tag ruleset matching `af-operations/**` with **Restrict updates** and **Restrict deletions** enabled. Marker creation remains necessary for the atomic transaction; subsequent updates/deletions should be denied except to explicitly configured bypass actors. Bypass membership and effective enforcement must be reviewed and minimized.

Official documentation:
- GraphQL `updateRefs` atomicity and zero-OID precondition: https://docs.github.com/en/graphql/reference/git
- Rulesets target branches/tags and tag rulesets can restrict updates/deletions: https://docs.github.com/en/repositories/configuring-branches-and-merges-in-your-repository/managing-rulesets/about-rulesets and https://docs.github.com/en/repositories/configuring-branches-and-merges-in-your-repository/managing-rulesets/available-rules-for-rulesets

**Still unverified for this repository:** effective tag rulesets, organization-level policy, bypass actors, and whether the exact GraphQL `updateRefs` mutation is subject to the intended tag protection in this account/repository configuration. The prior REST branch-protection 403 does not answer these questions. The empty repository-level rulesets response does not prove no organization ruleset applies.

### Revised gate

Before any disposable integration mutation:
1. An administrator must create or confirm an active tag ruleset matching `af-operations/**`.
2. Verify restrict-updates and restrict-deletions are enabled and bypass actors are explicitly limited.
3. Read back the effective rule for a representative marker tag/ref through an authorized view.
4. Verify a second actor cannot update/delete a marker in a disposable repository (negative tests), while the designated executor can create a new marker.
5. Confirm the two-entry GraphQL `updateRefs` transaction is rejected atomically when the marker create or branch CAS precondition fails.

Until these are established, marker immutability is **Unknown**, the design remains **Proposed**, and no real mutation is authorized or attempted.


### Least-privilege tag ruleset configuration

The routine executor needs to create a marker tag once, but should not be able to update or delete it afterward. The proposed ruleset is:

- Target tag pattern: `af-operations/*`.
- Enforcement: Active.
- Restrict updates: enabled.
- Restrict deletions: enabled.
- Restrict creations: disabled, so the authorized executor can create a new marker without receiving a bypass exemption.
- No routine bypass actors for update or deletion.

GitHub documents creation, update, and deletion restrictions as separate rules. This configuration is a proposed least-privilege design, not a verified live ruleset. Effective behavior of GraphQL `updateRefs` against this tag ruleset must still be tested in a disposable repository, including negative update and deletion attempts after creation.

Routine operation execution should have create-once and read/verify capabilities only. Any exceptional administrator override must be separately authorized and audited.


### Read-only repository inspection (2026-11-04)

The GitHub REST ruleset listing was repeated with `includes_parents=true`. It returned an empty array. This is stronger than the earlier repository-only listing: the endpoint reported no repository rulesets and no inherited organization/enterprise rulesets visible to this connection for this repository at the time of inspection. The matching-ref query for `refs/tags/af-operations/` returned an empty array; no operation marker tags currently exist. The working branch head was confirmed as `5df2fcfeee6613793feb1dcc0eb684650f982225`.

The official GraphQL schema documents a read path for effective rules on a ref (`Ref.rules`) and a non-admin-visible `refUpdateRule`; it also documents repository rulesets with `includeParents`. These are potentially useful for an effective-policy read, but the connected GitHub action surface available in this workflow exposes REST GET fetches and selected prebuilt GraphQL operations, not an arbitrary GraphQL query executor. Therefore no GraphQL effective-rule query was executed, and no effective tag protection is verified.

**Current conclusion:** no visible applicable ruleset is configured, and there are no marker tags. The proposed tag-protection model is not active. Do not execute the two-ref `updateRefs` operation in this repository until an administrator configures and verifies the intended protection in an explicitly designated test environment. A disposable repository and a supported GraphQL query/mutation path are still needed to test the exact behavior of `updateRefs` under tag rulesets.


### Effective-rule API distinction (2026-11-04)

The official REST "Get rules for a branch" endpoint returns active rules from repository and organization levels, but its path parameter is explicitly a branch name; the documented endpoint is `GET /repos/{owner}/{repo}/rules/branches/{branch}`. It is not a tag-effective-rules endpoint. The REST rules documentation does not document a parallel `/rules/tags/{tag}` route. Therefore it must not be used to claim effective tag protection.

For a tag ref, the documented GraphQL route is `Ref.rules`, described as active Repository and Organization rulesets that apply to that ref. `Ref.refUpdateRule` is described as branch-protection rules viewable by non-admins and should not be treated as a substitute for tag ruleset evaluation. The schema documents `updateRefs` as atomic across the supplied ref updates, with `beforeOid` preconditions.

The connected GitHub tool surface still has no arbitrary GraphQL query/mutation executor. The previous REST read of repository rulesets with `includes_parents=true` returned no rulesets; this is useful evidence that none were visible through that endpoint, but the exact effective tag-ref rule evaluation remains unqueried. Treat tag protection as **unverified / not configured in the inspected repository**, not as a tested enforcement guarantee.


### Next read-only GraphQL checks

A second REST inspection using `includes_parents=true&targets=tag` returned `[]`; the marker-tag ref listing also returned `[]`. No tag-target rulesets or marker tags are visible in this repository.

For a future designated disposable repository, query the GraphQL ruleset inventory:

```graphql
query TagRulesets($owner: String!, $name: String!) {
  repository(owner: $owner, name: $name) {
    rulesets(first: 100, includeParents: true, targets: [TAG]) {
      nodes {
        name target enforcement
        source { __typename }
        conditions { refName { include exclude } }
        rules(first: 100) {
          nodes { id type parameters { __typename } }
          pageInfo { hasNextPage endCursor }
        }
        bypassActors(first: 100) {
          nodes { bypassMode actor { __typename } }
          pageInfo { hasNextPage endCursor }
        }
      }
      pageInfo { hasNextPage endCursor }
    }
  }
}
```

Then, once an existing test tag matches the ruleset condition, query its effective rules:

```graphql
query EffectiveTagRules($owner: String!, $name: String!, $qualifiedName: String!) {
  repository(owner: $owner, name: $name) {
    ref(qualifiedName: $qualifiedName) {
      name prefix
      rules(first: 100) {
        nodes {
          id type parameters { __typename }
          repositoryRuleset {
            name target enforcement
            source { __typename }
            conditions { refName { include exclude } }
            rules(first: 100) {
              nodes { id type parameters { __typename } }
              pageInfo { hasNextPage endCursor }
            }
            bypassActors(first: 100) {
              nodes { bypassMode actor { __typename } }
              pageInfo { hasNextPage endCursor }
            }
          }
        }
        pageInfo { hasNextPage endCursor }
      }
    }
  }
}
```

Example CLI call for the inventory query (replace placeholders):

```sh
gh api graphql -f query='query($owner:String!,$name:String!){repository(owner:$owner,name:$name){rulesets(first:100,includeParents:true,targets:[TAG]){nodes{name target enforcement source{__typename} conditions{refName{include exclude}} rules(first:100){nodes{id type parameters{__typename}} pageInfo{hasNextPage endCursor}} bypassActors(first:100){nodes{bypassMode actor{__typename}} pageInfo{hasNextPage endCursor}}} pageInfo{hasNextPage endCursor}}}}' -F owner='OWNER' -F name='REPOSITORY'
```

The effective-ref query requires an existing matching tag; a null ref proves nothing about enforcement. Handle pagination if any connection reports additional pages. Neither read query proves that `updateRefs` obeys the ruleset: that requires a controlled mutation test in the disposable repository. These queries are documented, not executed here.


### Disposable-repository acceptance matrix

Run only after the test repository owner has explicitly designated the repository and approved the test window. Use a fresh, uniquely named test tag under the exact protected pattern.

| Case | Attempt | Required observation |
|---|---|---|
| RQ-01 | Inventory query | Every applicable repository/parent TAG ruleset is returned; paginate all connections |
| RQ-02 | Effective-ref query | Matching tag returns the expected active rules; paginate all connections |
| POS-01 | Create a matching marker tag with the intended create-only executor | Creation succeeds; effective rules still report update/delete restrictions |
| NEG-01 | Update the existing marker tag to a different commit | Rejected; original tag target remains unchanged |
| NEG-02 | Delete the existing marker tag | Rejected; tag remains present |
| NEG-03 | Attempt update/delete as the ordinary test actor | Rejected; no routine bypass |
| CAS-01 | Submit atomic branch-CAS + marker-create with correct before OIDs | Both refs change, or neither does |
| CAS-02 | Repeat the exact operation after success | The old branch OID and existing marker precondition prevent a second application |
| CAS-03 | Change the branch independently before submission | Entire mutation rejected; no marker created and no force update |
| CAS-04 | Cause one member of the two-ref mutation to violate its precondition | Neither ref changes |

For every negative case, capture the GraphQL error, re-read both refs, and confirm no partial mutation. A rejected mutation alone is insufficient evidence of atomic non-change. For each positive case, capture the before/after OIDs and the returned marker target. Clean-up of test refs is a separate explicitly approved action; do not weaken protections or use an unapproved bypass to remove them.

**Acceptance rule:** the adapter remains blocked until RQ-01/RQ-02, POS-01, NEG-01/02/03, and CAS-01 through CAS-04 have been executed and their read-backs independently verified. Any missing result is **Unknown**, not Pass.


### Read-only test-repository discovery (2026-11-04)

The connected GitHub account's repository inventory was checked by owner, collaborator, and organization-member affiliation. The owner inventory returned only `mcoke9-lgtm/Yggdrasil-Lean`; collaborator and organization-member inventories returned no repositories. No existing disposable repository is currently visible to this connection.

The repository metadata confirms administrative permission on the existing project, but that is not a substitute for an explicit decision to create or repurpose a test repository. The existing project is not designated disposable, and its branch/ref history must remain untouched by enforcement experiments.

The available GitHub action surface was also rechecked. It supports read-only REST fetches and selected prebuilt GraphQL operations, but exposes no general GraphQL query or `updateRefs` mutation executor. Therefore neither the inventory query nor effective-ref query can be run through this connection, and the acceptance matrix cannot be truthfully marked tested.

**Current blocker, now narrowed:** provide/designate a disposable test repository and a GraphQL-capable authenticated execution path. No repository was created, repurposed, or modified for testing.
