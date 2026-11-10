# Checkpoint 1 status

- MRP-001 Revision 4: adopted; unchanged.
- Core source and acceptance tests A1–A10: committed.
- Reconciliation: committed as a read-only evidence comparison draft. It checks record identity, creation type, exact digest, unique event identity, and non-negative integer sequence representation.
- Reconciliation edge case: evidence with the expected record identity but a missing event ID is retained as INCOMPLETE, rather than misreported as NOT_FOUND.
- Reconciliation precedence tests cover CONFLICT over AMBIGUOUS, AMBIGUOUS over INCOMPLETE, and UNCERTAIN over INCOMPLETE.
- Snapshot storage: strict JSON reading and atomic replacement are implemented and tested.
- MemoryStore persistence: detached snapshot export/import preserves record identities, version history, parent relationships, and directional conflict annotations.
- Snapshot import hardening: rejects boolean format versions, parentless non-initial versions, duplicate parents, invalid parent ordering, duplicate conflict annotations, and malformed/empty conflict endpoint identities before lookup.
- Package verifier hardening: rejects duplicate ZIP member names, unsafe/absolute/traversal manifest paths, duplicate/reserved paths, malformed entries, invalid sizes, and malformed SHA-256 strings.
- Interrupted-write recovery tests: simulated replacement failure and fsync failure preserve the previous complete snapshot and clean temporary files; a failed first write leaves no partial destination.
- Full suite: **50 tests passed** in GitHub Actions on CPython 3.13.18 and independently reproduced from the downloaded portable package in a separate local container on CPython 3.13.5.
- Package verification: embedded SHA-256 manifest independently checked against all 15 packaged project files; deterministic package build and tamper tests passed.
- Test status: tested and independently reproduced in two execution environments.
- Review status: internal adversarial review and recovery hardening completed; no independent third-party code review yet. Reconciliation and snapshot format remain implementation drafts.
- Scope limit: crash behavior tested through simulated replacement failure, not power-loss or filesystem fault injection. No signed release, automatic backup, multi-device recovery, or network synchronization service.
- Next: independent review and recovery-focused testing, then decide whether to adopt this as the canonical reproducible Core Memory System release.
