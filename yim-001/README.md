# Core Memory System (YIM-001) — Checkpoint 1

**Status:** Working reference implementation; latest verified test count is recorded in the PR validation section.  
**Cooperative AI Architecture:** wave form; MRP-001 Working Baseline 2026-11, Revision 4 is its adopted shared records contract.  
**Core Memory System:** particle form; YIM-001 is the canonical reproducible reference implementation under development.

The implementation includes an in-memory records core, read-only reconciliation draft, strict JSON snapshot storage, validated snapshot import/export, hardened deterministic portable packaging, provenance and audited-memory layers, cross-instance exchange, explicit recovery backups, and guarded restore.

## Included capabilities

- Stable record and version identifiers.
- Initial record creation and non-destructive revision.
- Individual version retrieval and parent relationships.
- Directional conflict annotations.
- Recursive strict-JSON validation before writes, including rejection of NaN and infinities.
- Six-outcome read-only creation-event reconciliation draft.
- Strict JSON snapshot loading, including rejection of non-standard numeric constants and duplicate object keys.
- Atomic snapshot replacement using a same-directory temporary file, file flush, and fsync before replacement.\n- Explicit digest-protected recovery backup envelopes and guarded restore that preserves the exact prior destination bytes before replacement.
- MemoryStore snapshot export/import preserving records, versions, parent relationships, and directional conflicts.
- Deterministic portable ZIP generation and SHA-256 verification.

## Run tests

From this directory:

```sh
PYTHONPATH=src python -m unittest discover -s tests -v
```

## Save and load a memory store

```python
from yim001 import MemoryStore, save_store, load_store

store = MemoryStore()
record = store.create_record({"hello": "world"})
save_store(store, "memory.json")
restored = load_store("memory.json")
assert restored.get_version(record.version_id).content == {"hello": "world"}
```

## Build and verify a portable source package

From the repository root:

```sh
python yim-001/scripts/package.py --output dist/YIM-001-Core-Memory-System-checkpoint-1.zip
python yim-001/scripts/package.py --verify dist/YIM-001-Core-Memory-System-checkpoint-1.zip
```

The ZIP contains a SHA-256 manifest for every packaged project file. GitHub Actions builds, verifies, and uploads the ZIP as a workflow artifact.

## Boundaries

This is a local reference implementation, not yet a distributed synchronization service. It does not implement networking, multi-system conflict resolution, cryptographic signatures, identity/access control, automatic backup scheduling, remote backup custody, or recovery across devices. Snapshot atomicity is tested through simulated replacement failure; it is not a guarantee against every hardware or filesystem failure. Reconciliation and snapshot format remain subject to further review.

## Portable command-line interface

The local store can be operated without writing a Python integration. From the repository root:

```sh
PYTHONPATH=yim-001/src python -m yim001 --help
PYTHONPATH=yim-001/src python -m yim001 init ./memory.json
PYTHONPATH=yim-001/src python -m yim001 create ./memory.json content.json
PYTHONPATH=yim-001/src python -m yim001 show ./memory.json --record RECORD_ID
PYTHONPATH=yim-001/src python -m yim001 verify ./memory.json
```

See [CLI.md](CLI.md) for revision, branching/merge parents, metadata, directional conflicts, and operational boundaries. The CLI is a local single-writer tool, not a network synchronization service.

## Provenance event ledger

A separate hash-linked event ledger records caller-supplied action claims while leaving memory records and MRP-001 Revision 4 unchanged. Start with:

```sh
PYTHONPATH=yim-001/src python -m yim001 event-init ./events.json
PYTHONPATH=yim-001/src python -m yim001 event-append ./events.json record.created local-node --record RECORD_ID --version VERSION_ID
PYTHONPATH=yim-001/src python -m yim001 event-verify ./events.json
```

See [PROVENANCE.md](PROVENANCE.md) for its event schema, trust boundaries, and current limitations.

## Audited memory operations

The additive `AuditedMemoryStore` facade automatically links successful create, revise, and conflict-annotation operations to provenance events. It persists the Core Memory snapshot and event ledger together in one combined atomic JSON file:

```python
from yim001 import AuditedMemoryStore, save_audited, load_audited

memory = AuditedMemoryStore("local-node")
first = memory.create_record({"hello": "world"})
second = memory.revise(first.record_id, {"hello": "again"})
memory.annotate_conflict(first.version_id, second.version_id)
save_audited(memory, "audited-memory.json")
restored = load_audited("audited-memory.json")
```

See [AUDITED-MEMORY.md](AUDITED-MEMORY.md) for transaction behavior, tests, and trust boundaries. This layer does not change MRP-001 Revision 4 or the Core Memory snapshot format.

## Audited CLI

The portable CLI also supports a single combined memory-and-provenance file:

```sh
PYTHONPATH=yim-001/src python -m yim001 audited-init ./audited.json local-node
PYTHONPATH=yim-001/src python -m yim001 audited-create ./audited.json content.json
PYTHONPATH=yim-001/src python -m yim001 audited-revise ./audited.json RECORD_ID content.json
PYTHONPATH=yim-001/src python -m yim001 audited-show ./audited.json --events
PYTHONPATH=yim-001/src python -m yim001 audited-verify ./audited.json
```

Use `audited-annotate-conflict` for directional conflict annotations. See [CLI.md](CLI.md) and [AUDITED-MEMORY.md](AUDITED-MEMORY.md) for full usage and boundaries.

## Recovery backups and guarded restore

The additive recovery layer creates digest-protected backups of complete audited snapshots. Saving a backup never overwrites an existing backup path. Restore is a separate operation requiring explicit authorization; when replacing an existing destination, it first preserves the exact previous bytes to a new, exclusive preservation file. See [RECOVERY.md](RECOVERY.md) for the API, safeguards, and limitations.

Recovery remains local and single-writer. It does not provide automatic scheduling, encryption, remote redundancy, or proof that a selected backup is the newest or most trustworthy copy.

## Portable recovery demonstration

Run `PYTHONPATH=yim-001/src python yim-001/scripts/recovery_demo.py` for a reproducible two-copy file-level demonstration. It verifies intact copies against a separately retained checkpoint file, deliberately corrupts one copy, detects the damage, restores from the intact copy, and preserves the exact pre-restore bytes. This is a local simulation, not proof of independent custody or off-device resilience. See [RECOVERY-DEMO.md](RECOVERY-DEMO.md).
