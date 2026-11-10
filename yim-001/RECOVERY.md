# YIM-001 recovery backups and guarded restore

**Status:** Additive draft implementation. It does not change MRP-001 Revision 4, the Core Memory snapshot format, the audited snapshot format, or exchange-envelope format.

## Purpose

Recovery is separate from ordinary save and exchange. A backup is a detached, validated copy of one complete audited memory snapshot (memory plus provenance). A restore is a separate operation and requires explicit authorization.

The recovery envelope contains:

- format identifier and integer format version;
- caller-supplied source instance identifier;
- timezone-qualified creation timestamp;
- complete audited snapshot;
- canonical SHA-256 digest over the payload.

Inspection verifies the digest and validates the complete nested audited snapshot and provenance chain. The digest detects accidental or uncoordinated content changes; it is not a signature and does not establish authorship or custody.

## API

```python
from yim001 import (
    AuditedMemoryStore,
    create_recovery_backup,
    inspect_recovery_backup,
    save_recovery_backup,
    load_recovery_backup,
    restore_recovery_backup_file,
    save_audited,
)

memory = AuditedMemoryStore("local-actor")
memory.create_record({"important": "content"})

backup = create_recovery_backup(memory, "phone-instance")
save_recovery_backup(backup, "backups/phone-before-change.json")

checked = load_recovery_backup("backups/phone-before-change.json")
assert checked["audited_snapshot"] == memory.to_snapshot()

# If destination exists, preserve its exact bytes at a new path first.
result = restore_recovery_backup_file(
    "backups/phone-before-change.json",
    "memory/audited.json",
    authorized=True,
    preserve_path="quarantine/audited-before-restore.bin",
)
```

For a missing destination, `preserve_path` must be omitted. For an existing destination, a new `preserve_path` is mandatory.

## Safety behavior

- Backup creation is detached; it does not mutate the live memory instance.
- Backup file creation is exclusive: an existing backup path is never overwritten.
- Backup loading rejects malformed strict JSON, duplicate keys, non-JSON numeric constants, digest mismatch, unsupported format/version, invalid audited snapshots, and invalid provenance chains.
- Restore requires the exact boolean `authorized=True`; the authorization check occurs before backup or destination file access.
- Restore validates the backup before touching the destination.
- If a destination exists, restore first copies its exact raw bytes to a new preservation file. This works even if the existing destination is malformed or cannot be parsed as an audited snapshot.
- A pre-existing preservation path is never overwritten. If preservation fails, the destination is not replaced.
- Only after preservation succeeds does restore atomically replace the destination with the validated audited snapshot.
- A missing destination can be created from a validated backup without a preservation copy.
- Restore does not silently merge histories, discard divergent branches, or append a synthetic recovery event to the restored history. The backup's original provenance chain is preserved as-is.
- No backups are automatically deleted or rotated.

## Operational limits

- This is a local file API, not an automated backup schedule or remote backup service.
- File operations assume serialized single-writer access. There is no inter-process lock, compare-and-swap, or concurrent-writer protection.
- Same-directory temporary writes and atomic replacement reduce partial-write risk, but do not guarantee survival against every hardware, filesystem, controller, or power-loss failure.
- The exclusive backup/preservation file creation uses a same-filesystem hard link to publish a fully written temporary file without overwriting an existing path. Filesystems that do not support this operation will fail closed.
- No encryption, access control, identity authentication, digital signature, remote custody, or off-device redundancy is provided.
- Backup files contain the full memory and provenance data and may be sensitive. Protect their storage and transfer manually.
- Recovery cannot establish that a backup is the newest or most trustworthy copy. The caller must choose the intended backup based on external custody/checkpoint information.

## Test coverage

The recovery tests cover detached backup creation, digest tampering, timezone requirements, strict load/save round trips, refusal to overwrite backup files, authorization-before-I/O, preservation of exact corrupt destination bytes, mandatory preservation for existing destinations, preservation-path collision, creation of a missing destination, and file-based restore.
