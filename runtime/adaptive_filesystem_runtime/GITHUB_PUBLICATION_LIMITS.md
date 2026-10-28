# GitHub branch publication: conditional-update boundary

## Finding

The GitHub REST "Update a reference" endpoint accepts the desired commit SHA and a
`force` flag, but its documented request has no expected-current-head parameter.
A client-side sequence of read-head, compare, then update-ref is therefore not an
atomic compare-and-swap: another writer can move the branch between the read and
the update.

Official endpoint documentation:
https://docs.github.com/en/rest/git/refs#update-a-reference

The connector's `expected_sha` option is a wrapper-level feature, not a documented
parameter of the underlying REST request. Until its implementation and race behavior
are independently established, it must not be treated as server-enforced CAS.

## Implemented Git transport path

`git_transport_publication.py` provides an opt-in adapter using
`git push --force-with-lease=refs/heads/<branch>:<expected-sha>`. The lease names
the exact expected old object ID; a mismatch is rejected by the Git ref-update
mechanism. The adapter distinguishes a confirmed stale head from an unresolved
push failure and does not blindly retry.

`test_git_transport_publication.py` exercises successful publication and stale
publisher rejection against a disposable local bare Git remote. This validates the
adapter's Git transport behavior in that test environment, not the behavior of a
live GitHub-hosted repository.

Operational preconditions: Git must be installed; the local repository must contain
the candidate commit; the configured remote and credentials must be correct; the
caller must supply the exact expected old SHA and intended branch. Use a disposable
branch for any live-host validation before relying on this adapter operationally.

## Live-host test harness

`.github/workflows/live-stale-publisher-cas.yml` adds a manually dispatched test
that creates a unique temporary ref, publishes an intervening commit with a current
lease, attempts a stale update with the old lease, verifies that GitHub retained the
intervening head, and attempts cleanup. It uses a fixed test-ref prefix, has no
user-supplied branch input, and is restricted to `refs/heads/main`.

The workflow needs `contents: write` only to create and delete its temporary ref.
It is intentionally `workflow_dispatch`-only; it must be reviewed and present on
the default branch before it can be launched. Do not run it from an untrusted branch.
A cleanup warning means the temporary ref needs manual inspection and deletion.

## Required safe behavior

- Do not describe a read-then-write check as atomic publication fencing.
- Prefer a mechanism that actually enforces an expected old object ID atomically,
  such as Git transport ref update with a lease.
- If only the GitHub REST ref endpoint is available, classify publication as
  best-effort and do not claim stale-writer exclusion.
- After an ambiguous response, re-read the ref and classify the observed outcome;
  if the desired head is already present, reconcile it as published; otherwise
  distinguish a changed head from an unchanged or unreadable head and never
  blindly retry an uncertain write.
- Pure publication contract and fake-adapter tests remain contract tests only.
  Local bare-remote integration tests improve coverage but do not establish live
  GitHub integration behavior.

## Status

- Local Git compare-and-swap / lease primitive: verified in a disposable local test.
- Git transport adapter + local bare-remote integration tests: implemented; the
  regression suite also covers a failed push acknowledgement followed by read-back
  of the desired head. Runtime tests and Lean build passed on commit
  `2ff0c58666623778352dea7fa11f1d23e0cee40f`.
- Lean build on that same commit: passed.
- GitHub REST expected-head atomicity: unsupported by the documented endpoint shape.
- Connector `expected_sha` behavior: unverified. A controlled stale-update attempt
  against the disposable test branch returned a generic GraphQL error; read-back
  confirmed the branch stayed at the newer head. That outcome is safe, but it does
  not prove whether the connector rejected the stale lease atomically or failed
  before applying the request.
- Live-host test harness: added on the draft feature branch; not yet available to
  dispatch until the workflow is reviewed and merged to the default branch.
- End-to-end live GitHub stale-publisher rejection: not verified.

The adapter is a tested prototype, not a production deployment or a live GitHub
verification claim.
