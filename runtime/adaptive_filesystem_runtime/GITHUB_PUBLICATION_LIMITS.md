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

## Required safe behavior

- Do not describe a read-then-write check as atomic publication fencing.
- Prefer publishing through a mechanism that actually enforces an expected old
  object ID atomically, such as a Git transport ref update with a lease, where the
  transport and server behavior are verified.
- If only the GitHub REST ref endpoint is available, classify publication as
  best-effort and do not claim stale-writer exclusion.
- After an ambiguous response, re-read the ref and classify the observed outcome;
  never blindly retry an uncertain write.
- Keep the pure publication contract and fake-adapter tests as contract tests only.
  They do not establish live GitHub integration behavior.

## Status

- Local Git compare-and-swap / lease primitive: locally verified in a disposable test.
- Pure publication contract and regression tests: CI passed on the feature branch.
- GitHub REST expected-head atomicity: unsupported by the documented endpoint shape.
- Connector `expected_sha` behavior: unverified due generic errors and absent
  evidence of server-side atomic enforcement.
- End-to-end live GitHub stale-publisher rejection: not verified.

This document records a limitation; it does not implement a production publisher.
