# Checkpoint 1 status

- MRP-001 Revision 4: adopted; unchanged.
- Core source: committed to the checkpoint branch.
- Acceptance tests A1–A10: committed.
- Core edge-case tests: committed.
- Reconciliation: committed as a read-only evidence comparison draft. It checks record identity, creation type, exact digest, unique event identity, and non-negative integer sequence representation.
- Reconciliation edge case: evidence with the expected record identity but a missing event ID is retained as INCOMPLETE, rather than misreported as NOT_FOUND.
- Reconciliation precedence: tests exercise CONFLICT over AMBIGUOUS, AMBIGUOUS over INCOMPLETE, and UNCERTAIN over INCOMPLETE.
- Snapshot storage: strict JSON reading and atomic replacement are implemented and tested.
- MemoryStore persistence: detached snapshot export/import now preserves record identities, version history, parent relationships, and directional conflict annotations. Import validates graph membership and parent ordering.
- Interrupted-write test: simulated replacement failure; the previous complete snapshot remained readable and the temporary file was cleaned up.
- Full suite: **38 tests passed** in GitHub Actions on CPython 3.13.18, Ubuntu runner, commit `b39cd7379f00b9e3d674c68699c1044d0b415979`.
- Workflow run: https://github.com/mcoke9-lgtm/Yggdrasil-Lean/actions/runs/42849692654
- Scope limit: snapshot persistence is now integrated with MemoryStore, but crash behavior has been tested through simulated replacement failure, not through power-loss or filesystem fault injection.
- Test status: tested in one independent CI execution environment; a second independent reproduction has not been performed.
- Review status: not independently reviewed; reconciliation and snapshot format remain implementation drafts.
- Packaging: an earlier portable ZIP was reported, but its contents and hash have not been reverified against this checkpoint.
- Next: create a deterministic portable export and SHA-256 manifest, then verify the package can be imported and reproduces the same memory graph.
