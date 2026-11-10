# Checkpoint 1 status

- MRP-001 Revision 4: adopted; unchanged.
- Core source: committed to the checkpoint branch.
- Acceptance tests A1–A10: committed.
- Core edge-case tests: committed.
- Initial core-only local test result: 14 tests reported passed in the initial checkpoint; not independently reproduced at that time.
- Reconciliation: committed as a read-only evidence comparison draft. It checks record identity, creation type, exact digest, unique event identity, and non-negative integer sequence representation.
- Reconciliation edge case: evidence with the expected record identity but a missing event ID is retained as INCOMPLETE, rather than misreported as NOT_FOUND.
- Full suite: **27 tests passed** in GitHub Actions on CPython 3.13.18, Ubuntu runner, commit `2f4eb299c3714067801e1194aa6178ddc49a3f53`.
- Workflow run: https://github.com/mcoke9-lgtm/Yggdrasil-Lean/actions/runs/42848997790
- Test status: tested in one independent CI execution environment; a second independent reproduction has not been performed.
- Review status: not independently reviewed; the reconciliation implementation remains a draft pending broader edge-case review.
- Packaging: an earlier portable ZIP was reported, but its contents and hash have not been reverified against this checkpoint.
- Next: continue testing precedence and malformed-history combinations; then implement and test durable interrupted-write handling before producing a fresh SHA-256 manifest and reproducible export.
