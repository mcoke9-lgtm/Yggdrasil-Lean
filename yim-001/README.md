# Core Memory System (YIM-001) — Checkpoint 1

**Status:** Proposed implementation; 14 local tests passed; independent reproduction pending.  
**Cooperative AI Architecture:** wave form; MRP-001 Working Baseline 2026-11, Revision 4 is its adopted shared records contract.  
**Core Memory System:** particle form; YIM-001 is its current reproducible reference implementation.

This checkpoint implements the in-memory records core only. It deliberately does not implement persistence, provenance, events, integrity sealing, recovery, networking, access control, or the six-outcome reconciliation layer.

## Requirements covered

- Stable record and version identifiers.
- Initial record creation and non-destructive revision.
- Individual version retrieval and parent relationships.
- Directional conflict annotations.
- Recursive strict-JSON validation before writes, including rejection of NaN and infinities.
- In-memory implementation using only the Python standard library.

The implementation also provides immutable version snapshots, collision-checked UUID allocation, detached JSON snapshots, and visible competing tips as implementation quality choices. These are not all mandatory MRP-001 requirements.

## Run tests

From this directory:

```sh
PYTHONPATH=src python -m unittest discover -s tests -v
```

## Limitations

This is an in-memory reference implementation. Data is lost when the process exits. No claim is made about durable writes, interrupted-write recovery, cryptographic integrity, provenance, or cross-system synchronization.
