# YIM-001 cross-instance exchange

**Status:** First portable-envelope and read-only staging implementation committed; CI verification pending. No merge/apply operation exists.

## Current API

```python
from yim001 import (
    AuditedMemoryStore, export_envelope, inspect_envelope, stage_exchange,
    save_envelope, load_envelope,
)

source = AuditedMemoryStore("source-actor")
first = source.create_record({"hello": "world"})
envelope = export_envelope(source, "source-instance")
save_envelope(envelope, "transfer.json")

validated = load_envelope("transfer.json")
destination = AuditedMemoryStore("destination-actor")
report = stage_exchange(validated, destination)
assert report["mutated_destination"] is False
```

The envelope carries a caller-supplied source-instance identifier, timezone-qualified export timestamp, complete audited snapshot, and canonical SHA-256 digest. Inspection verifies the digest and validates the nested memory snapshot and provenance chain.

Staging compares record, version, and event identities. It reports incoming identities, shared evidence, and same-ID/different-content collisions. It preserves the source event stream as its own sequence and does not concatenate it with the destination ledger. It is read-only: no source or destination mutation, no automatic merge, and no authorization to apply changes.

## Identity and history rules

- Record IDs, version IDs, event IDs, parent relationships, and directional conflict annotations are preserved exactly in the transfer payload.
- Same version identity with differing content, metadata, or parent relationships is a hard collision.
- Same event identity with differing event data or digest is a hard collision.
- Versions found only in the incoming snapshot are reported as additions; they are not applied.
- Event streams remain independent. There is no invented global sequence.
- A matching digest establishes canonical content equality, not authorship, actor identity, custody, or trustworthiness.

## Current boundaries and known gaps

- This is a local file/API exchange primitive, not network synchronization.
- Source instance IDs and actor IDs are caller-supplied, unauthenticated claims.
- SHA-256 detects payload alteration when the expected digest is trusted; it is not a signature and cannot prevent an attacker from replacing both payload and digest.
- No encryption, authentication, remote trust, automatic backup, concurrent-writer locking, merge/apply operation, or conflict-resolution policy is implemented.
- The current staging report compares record/version/event identities. Rich semantic comparison of every record-history relationship across partially overlapping histories remains a follow-up.
- Transfer files may contain sensitive user content; nothing is uploaded or transmitted automatically.

## Initial test coverage

The new exchange tests cover detached export, validation round-trip, preservation of version/event identities and directional conflicts, read-only staging, repeated-import/shared-evidence classification, version identity collision, tampered payload rejection, timezone-qualified timestamps, and independent event streams.

CI must pass on the latest branch head before this milestone is called tested or verified.

## Deferred next milestone

Implement a richer deterministic reconciliation proposal for partially overlapping/divergent histories, then (only with separate authorization) a transactional apply operation that preserves source snapshots and all original provenance evidence.