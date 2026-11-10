# Checkpoint 1 status

- MRP-001 Revision 4: adopted; unchanged.
- Core source: committed to the checkpoint branch.
- Acceptance tests A1–A10: committed.
- Core edge-case tests: committed.
- Initial core-only local test result: 14 tests reported passed in the initial checkpoint; not independently reproduced at that time.
- Reconciliation: committed as a read-only evidence comparison draft. It checks record identity, creation type, exact digest, unique event identity, and non-negative integer sequence representation.
- Reconciliation edge case: evidence with the expected record identity but a missing event ID is retained as INCOMPLETE, rather than misreported as NOT_FOUND.
- Reconciliation precedence: tests exercise CONFLICT over AMBIGUOUS, AMBIGUOUS over INCOMPLETE, and UNCERTAIN over INCOMPLETE.
- Snapshot storage primitive: committed. Strict JSON reading rejects non-standard numeric constants and duplicate object keys. Atomic writing stages and fsyncs a same-directory temporary file before replacement.
- Interrupted-write test: simulated replacement failure; the prior snapshot remained readable and the temporary file was cleaned up.
- Full suite: **36 tests passed** in GitHub Actions on CPython 3.13.18, Ubuntu runner, commit `e124b477938bfeed48f9113be1f12249e6b02ee8`.
- Workflow run: https://github.com/mcoke9-lgtm/Yggdrasil-Lean/actions/runs/42849401501
- Scope limit: snapshot helpers are tested primitives; they are not yet integrated with MemoryStore export/import or a complete durable memory snapshot.
- Test status: tested in one independent CI execution environment; a second independent reproduction has not been performed.
- Review status: not independently reviewed; reconciliation and storage remain implementation drafts.
- Packaging: an earlier portable ZIP was reported, but its contents and hash have not been reverified against this checkpoint.
- Next: integrate snapshot storage with MemoryStore export/import, test round-trip and interrupted writes end-to-end, then produce a fresh SHA-256 manifest and reproducible export.
