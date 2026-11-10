# Checkpoint 1 status

- MRP-001 Revision 4: adopted; unchanged.
- Core source: committed to the checkpoint branch.
- Acceptance tests A1–A10: committed.
- Reconciliation: committed as a read-only evidence comparison draft. It checks record identity, creation type, exact digest, unique event identity, and non-negative integer sequence representation.
- Reconciliation edge case: evidence with the expected record identity but a missing event ID is retained as INCOMPLETE, rather than misreported as NOT_FOUND.
- Reconciliation precedence: tests exercise CONFLICT over AMBIGUOUS, AMBIGUOUS over INCOMPLETE, and UNCERTAIN over INCOMPLETE.
- Snapshot storage: strict JSON reading and atomic replacement are implemented and tested.
- MemoryStore persistence: detached snapshot export/import preserves record identities, version history, parent relationships, and directional conflict annotations. Import validates graph membership and parent ordering.
- Interrupted-write test: simulated replacement failure; the previous complete snapshot remained readable and the temporary file was cleaned up.
- Deterministic package builder: committed. It creates a portable ZIP with a SHA-256 manifest, verifies every packaged file, and checks deterministic output.
- Full suite: **40 tests passed** in GitHub Actions on CPython 3.13.18 and independently reproduced from the portable package in a separate local container on CPython 3.13.5.
- Package verification: embedded SHA-256 manifest independently checked against all 14 packaged files; deterministic package builder also verified the ZIP.
- Scope limit: crash behavior has been tested through simulated replacement failure, not power-loss or filesystem fault injection.
- Test status: tested and independently reproduced in two execution environments.
- Review status: not independently reviewed; reconciliation and snapshot format remain implementation drafts.
- Next: obtain independent code review, broaden recovery and corruption tests, then assess whether this checkpoint is ready for adoption as the canonical reproducible Core Memory System release.
