# YIM-001 Registers

This package contains separate append-only registers for technical interpretation and observation lifecycle management.

## Human Understanding Register

The Human Understanding Register maps stable technical IDs to human-readable interpretations and naming proposals.

Focused tests:

```sh
PYTHONPATH=src python -m unittest discover -s tests -p "test_naming.py" -v
```

## Tracking Register

The Tracking Register preserves source observations, subsequent updates, assessments, and explicitly authorized adoption decisions without conflating those stages.

Focused tests:

```sh
PYTHONPATH=src python -m unittest discover -s tests -p "test_tracking.py" -v
```

The Tracking Register uses only the Python standard library. JSON snapshots are written atomically for a single writer, and each event is linked to the preceding event with SHA-256 and validated on load. Source retrieval outcomes (`unknown`, `available`, `partial`, `unavailable`) are retained as append-only events. Lifecycle-aware search returns matching items with their event histories, and JSON export preserves the full event chain. Malformed snapshots are rejected with `TrackingRegisterError`.

## Boundaries

- Observation does not imply truth, agreement, or endorsement.
- Tracking does not imply assessment.
- Assessment does not imply adoption.
- Adoption requires a distinct explicit authorization event after assessment.
- Corrections append events and preserve earlier observations.
- These local registers do not poll sources, send notifications, authenticate actors, synchronize devices, or provide multi-writer locking.
- Authorization fields are recorded assertions; a trusted host must enforce identity and permissions.

See [Tracking Register 001](../../docs/TRACKING_REGISTER_001.md) and the Human Understanding Register protocol for their separate responsibilities.
