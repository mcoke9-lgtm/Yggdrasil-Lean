# Checkpoint 1 status

- MRP-001 Revision 4: adopted; unchanged.
- Core source: committed to the checkpoint branch.
- Acceptance tests A1–A10: committed.
- Core edge-case tests: committed.
- Initial core-only local test result: 14 tests reported passed in the initial checkpoint; not independently reproduced at that time.
- Reconciliation: committed; validates sequence values as non-negative built-in integers and rejects booleans and floats.
- Reconciliation tests: committed, including negative, boolean, and float sequence cases.
- Full suite: **26 tests passed** in GitHub Actions on CPython 3.13.18, Ubuntu runner, commit `ada85ba9f6d30fd371a0ea611567beacff0a997e`.
- Workflow run: https://github.com/mcoke9-lgtm/Yggdrasil-Lean/actions/runs/42848625952
- Independent reproduction in a second execution environment: not performed.
- Packaging: an earlier portable ZIP was reported, but its contents and hash have not been reverified against this checkpoint.
- Next: broaden reconciliation tests and verify interrupted-write behavior, then produce and verify a reproducible export with a fresh SHA-256 manifest.
