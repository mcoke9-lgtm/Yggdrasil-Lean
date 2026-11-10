# YIM-001 provenance event ledger

The provenance ledger is a separate append-only sequence of event claims. It does not modify memory records or the MRP-001 Revision 4 contract.

## Event model

Each event contains:

- A unique event ID and zero-based sequence number.
- A controlled event type and caller-supplied actor/system identifier.
- An ISO-8601 timestamp that must include a UTC offset.
- Optional record and version identifiers.
- JSON details.
- The previous event's SHA-256 digest and this event's SHA-256 digest.

Supported initial event types are `record.created`, `record.revised`, `record.imported`, `record.exported`, `record.reconciled`, `conflict.annotated`, and `snapshot.recovered`.

## CLI

Create a ledger:

```sh
PYTHONPATH=yim-001/src python -m yim001 event-init ./events.json
```

Append an event:

```sh
PYTHONPATH=yim-001/src python -m yim001 event-append ./events.json record.created local-node --record RECORD_ID --version VERSION_ID --timestamp 2026-11-10T12:00:00Z
```

Verify the chain and inspect its events:

```sh
PYTHONPATH=yim-001/src python -m yim001 event-verify ./events.json
PYTHONPATH=yim-001/src python -m yim001 event-show ./events.json
```

Details can be supplied through a JSON file using `--details details.json`. When omitted, details default to an empty object.

## Important trust boundaries

- The actor/system identifier is a claim supplied by the caller; it is not authenticated.
- A SHA-256 hash chain detects accidental changes and alterations that are checked against a trusted prior digest. It does not provide a digital signature, prove authorship, or prevent an attacker from rewriting and re-hashing the entire ledger.
- Each independent system should maintain its own ledger identity and protect its checkpoints. Cross-ledger exchange and reconciliation are not implemented yet.
- Ledger writes use validated snapshots and atomic replacement. Treat the file as single-writer; concurrent writers are not coordinated.
- Events describe actions; they do not themselves cause or authorize memory mutations.
- The ledger is separate from the Core Memory snapshot. Linking an event to a record/version is explicit through identifiers, not automatic instrumentation of every store operation.
