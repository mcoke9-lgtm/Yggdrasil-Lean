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

## Combined audited memory store

For automatic event recording, initialize one combined memory-and-ledger file. The actor identifier is a caller-supplied label, not an authenticated identity:

```sh
PYTHONPATH=yim-001/src python -m yim001 audited-init ./audited.json local-node
printf '{"topic":"first note"}' > content.json
PYTHONPATH=yim-001/src python -m yim001 audited-create ./audited.json content.json
PYTHONPATH=yim-001/src python -m yim001 audited-revise ./audited.json RECORD_ID content.json
PYTHONPATH=yim-001/src python -m yim001 audited-annotate-conflict ./audited.json SOURCE_VERSION_ID TARGET_VERSION_ID
PYTHONPATH=yim-001/src python -m yim001 audited-show ./audited.json --record RECORD_ID
PYTHONPATH=yim-001/src python -m yim001 audited-show ./audited.json --events
PYTHONPATH=yim-001/src python -m yim001 audited-verify ./audited.json
```

The audited commands append provenance events for successful create, revise, and conflict annotation. Each write replaces one combined JSON snapshot containing both streams. Optional `--timestamp` arguments support reproducible tests and controlled imports; otherwise event timestamps are generated in UTC. A failed operation or event validation does not replace the existing combined file.

The audited file remains local and single-writer. It does not provide authenticated actors, concurrent-writer locking, network synchronization, or automatic backups. See [AUDITED-MEMORY.md](AUDITED-MEMORY.md).

## Capture supplied work into the Living Tree

The `audited-capture` command is the first explicit intake path that creates a memory record, preserves the submitted source content and language, assigns one primary logical location, adds optional contextual locations, and appends a provenance event as one in-memory transaction before saving the combined snapshot:

```sh
PYTHONPATH=yim-001/src python -m yim001 audited-capture ./audited.json content.json \
  --primary-location roots.archive \
  --context-location branches.project \
  --source-kind chat_exchange \
  --source-reference checkpoint-001 \
  --language English
```

The logical locations must already exist in the store. The command rejects an unknown location without creating a partial memory record. Supply `--language undetermined` when the language is not known; do not silently omit it. The source reference is caller-supplied and is not independently verified.

This is **explicit local intake**, not automatic ChatGPT conversation capture. It does not read chat history, discover new conversations, classify placement automatically, or synchronize across devices. Those remain implementation work. See [AUDITED-MEMORY.md](AUDITED-MEMORY.md) for the combined-store boundaries.


## Persistent intake handoff (awaiting placement)

A source-neutral local intake queue can preserve supplied work before a logical Tree location has been selected. This prevents an item from being silently discarded or forced into an arbitrary location:

```sh
PYTHONPATH=yim-001/src python -m yim001.intake_cli init ./intake.json phone-intake
PYTHONPATH=yim-001/src python -m yim001.intake_cli add ./intake.json content.json \\
  --source-kind chat_exchange \\
  --source-reference session-checkpoint-7 \\
  --language English
PYTHONPATH=yim-001/src python -m yim001.intake_cli list ./intake.json
```

Each packet retains the submitted JSON content, source kind/reference, language, capture time, and metadata, and is explicitly marked `awaiting_placement`. The queue refuses duplicate packet IDs and validates its structure on load. It is append-only at the packet level in this first version; it has no packet deletion, placement assignment, or automatic import into the audited memory store yet. To move a packet into the Living Tree, an operator must still select a valid logical location and use the audited capture path. This queue does not access ChatGPT conversation history or sync across devices; it is a concrete local handoff mechanism, not automatic capture.
