# Checkpoint 1 status

- MRP-001 Revision 4: adopted; unchanged.
- Core source: committed to the checkpoint branch.
- Acceptance tests A1–A10: committed.
- Core edge-case tests: committed.
- Initial core-only local test result: 14 tests reported passed in the initial checkpoint; not independently reproduced at that time.
- Reconciliation: committed as a read-only evidence comparison draft. It checks record identity, creation type, exact digest, unique event identity, and non-negative integer sequence representation.
- Reconciliation edge case: evidence with the expected record identity but a missing event ID is retained as INCOMPLETE, rather than misreported as NOT_FOUND.
- Reconciliation precedence: tests now exercise CONFLICT over AMBIGUOUS, AMBIGUOUS over INCOMPLETE, and UNCERTAIN over INCOMPLETE.
- Full suite: **30 tests passed** in GitHub Actions on CPython 3.13.18, Ubuntu runner, commit `153382cd5d9bbb56906682504cd25c87396a0e0d`.
- Workflow run: https://github.com/mcoke9-lgtm/Yggdrasil-Lean/actions/runs/42849178879
- Test status: tested in one independent CI execution environment; a second independent reproduction has not been performed.
- Review status: not independently reviewed; the reconciliation implementation remains a draft pending broader edge-case review.
- Packaging: an earlier portable ZIP was reported, but its contents and hash have not been reverified against this checkpoint.
- Next: implement durable snapshot writing with interrupted-write protection and strict JSON loading; test it before producing a fresh SHA-256 manifest and reproducible export.
