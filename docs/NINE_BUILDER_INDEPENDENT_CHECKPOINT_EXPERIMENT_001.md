# Nine Builder Independent Checkpoint Experiment 001

**Status:** Test implemented; CI execution pending.
**Branch:** `nine-builder-independent-checkpoint-experiment-001`
**Parent:** `nine-builder-disconnect-reconnect-test-001`
**Purpose:** Exercise separate, role-owned persistence and append-only reconciliation using the existing Android SQLite journal.

## What the experiment does

The Robolectric test creates nine distinct SQLite databases, one for each value in `BuilderRole`. Each database receives one initial checkpoint turn authored by its matching Builder identity. The test then closes all nine stores, reopens only Mari and Loki, and appends two different findings marked `DISPUTED`. The other seven stores remain closed during those updates.

After closing those two stores, the test reopens all nine databases, collects the local histories, orders the recovered turns by their recorded timestamps, and appends them to a separate reconciliation journal. It asserts that:
- all nine Builder identities remain represented;
- each initial checkpoint survives its local pause and reopen;
- both disconnected findings survive as separate records;
- neither finding overwrites the other; and
- the merged journal contains eleven unique turns.

## Evidence boundary

This is a local persistence and reconciliation experiment, not a distributed-agent demonstration.

The nine stores are separate SQLite database files within one Robolectric test process and one application sandbox. The test does not create nine operating-system processes, nine Android services, or nine physical devices. It does not test network partitions, authentication, signed events, concurrent writes to a shared database, permission freshness, causal conflict resolution, or autonomous Builder inference.

The two disputed updates are deliberately retained rather than automatically resolved. Reconciliation here means collecting and preserving records in one append-only journal; it does not mean deciding which finding is correct.

The checkpoint body is a small JSON payload stored inside the existing conversation turn. This experiment does not introduce or claim a finalized checkpoint schema or production synchronization API.

## Acceptance criteria

- Exactly nine role-specific local stores are created.
- Each initial record is authored by the matching Builder identity.
- Stores can be closed and reopened without losing their local record.
- Two roles can append while the other seven stores remain closed.
- Reconciliation retains all nine initial records and both later findings.
- Both unresolved findings remain visible and marked `DISPUTED`.
- No record is overwritten or silently promoted to `VERIFIED` or `ADOPTED`.

## Next gates

1. Run the Android JVM unit test suite in GitHub Actions.
2. If successful, extend the test from separate local databases to separately running worker processes.
3. Add authenticated node identity and explicit provenance for each checkpoint.
4. Test partition/rejoin, duplicate delivery, delayed messages, and divergent updates across separate nodes.
5. Define which conflicts may be preserved provisionally and which actions must wait for user authorization or fresh shared state.

## Integrity checkpoint

- **Count:** nine role stores, nine initial checkpoints, two disconnected updates, eleven merged records.
- **Role mapping:** one local database per declared `BuilderRole`; turn speaker must match that role.
- **Omissions/duplicates:** this test does not prove separate processes or devices; turn IDs are checked for uniqueness in the merged history.
- **Evidence status:** test implemented; execution pending until CI logs are inspected.
- **Correction rule:** preserve disputed findings; do not silently resolve or overwrite them.
- **Persistence:** isolated branch only; no merge to the parent branch or `main`, no deployment, and no claim of nine autonomous agents.
