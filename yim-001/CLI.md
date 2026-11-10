# YIM-001 portable command-line interface

The command-line interface makes the local reference memory store usable without writing a Python integration. It uses only the Python standard library and the existing YIM-001 snapshot format.

Run from the repository root:

```sh
PYTHONPATH=yim-001/src python -m yim001 --help
```

## Create a store and first record

```sh
PYTHONPATH=yim-001/src python -m yim001 init ./memory.json
printf '{"topic":"first note","body":"A durable memory record."}' > content.json
PYTHONPATH=yim-001/src python -m yim001 create ./memory.json content.json
```

The command prints the new record and version identifiers. Keep those identifiers when referring to the record elsewhere.

## Revise without erasing history

```sh
printf '{"topic":"first note","body":"Revised content."}' > content.json
PYTHONPATH=yim-001/src python -m yim001 revise ./memory.json RECORD_ID content.json
```

By default, a revision uses the record's current tips as its parents. Supply one or more explicit `--parent VERSION_ID` arguments to create a branch or merge revision.

Optional JSON metadata can be supplied with `--metadata metadata.json`.

## Read and verify

```sh
PYTHONPATH=yim-001/src python -m yim001 show ./memory.json --record RECORD_ID
PYTHONPATH=yim-001/src python -m yim001 show ./memory.json --version VERSION_ID
PYTHONPATH=yim-001/src python -m yim001 verify ./memory.json
```

## Record a directional conflict

```sh
PYTHONPATH=yim-001/src python -m yim001 annotate-conflict ./memory.json SOURCE_VERSION_ID TARGET_VERSION_ID
```

The annotation is directional; the command does not create a reciprocal annotation.

## Operational boundaries

- The store is a local JSON snapshot. It is not a network service or multi-device synchronization protocol.
- Treat a store file as single-writer: do not run concurrent mutating commands against the same file. Multi-process locking and concurrent-writer reconciliation are not implemented.
- Keep independent backup copies. This CLI does not schedule or upload backups.
- The snapshot contains memory content and metadata in readable JSON. Protect the file and its backups accordingly.
- `init` refuses to overwrite an existing path. Other write commands validate and load the existing snapshot before atomically replacing it.
- Invalid JSON input or a failed validation does not intentionally modify the existing store.
- The CLI does not change MRP-001 Revision 4 or expand its normative scope.

## Provenance event ledger

A separate event ledger records caller-supplied action claims without changing memory records:

```sh
PYTHONPATH=yim-001/src python -m yim001 event-init ./events.json
PYTHONPATH=yim-001/src python -m yim001 event-append ./events.json record.created local-node --record RECORD_ID --version VERSION_ID --timestamp 2026-11-10T12:00:00Z
PYTHONPATH=yim-001/src python -m yim001 event-verify ./events.json
PYTHONPATH=yim-001/src python -m yim001 event-show ./events.json
```

See [PROVENANCE.md](PROVENANCE.md) for the event model and trust boundaries. Hash links are integrity checks, not signatures or proof of actor identity. Each ledger is a separate single-writer stream; cross-ledger exchange is future work.
