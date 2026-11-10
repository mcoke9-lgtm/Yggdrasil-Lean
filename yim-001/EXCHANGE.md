# YIM-001 cross-instance exchange

**Status:** Portable envelope, deterministic read-only staging, and explicitly authorized transactional apply are implemented in the draft branch. CI verification is pending. No merge to the canonical branch or release adoption has occurred.

## Current API

```python
from yim001 import (
    AuditedMemoryStore, apply_exchange, export_envelope,
    inspect_envelope, stage_exchange, save_envelope, load_envelope,
)

source = AuditedMemoryStore("source-actor")
source.create_record({"hello": "world"})
envelope = export_envelope(source, "source-instance")
save_envelope(envelope, "transfer.json")

validated = load_envelope("transfer.json")
destination = AuditedMemoryStore("destination-actor")
proposal = stage_exchange(validated, destination)
assert proposal["mutated_destination"] is False

# Applying requires a separate, explicit authorization at the call site.
result = apply_exchange(validated, destination, authorized=True)
assert result["status"] == "applied"
```

The envelope carries a caller-supplied source-instance identifier, timezone-qualified export timestamp, complete audited snapshot, and canonical SHA-256 digest. Inspection verifies the digest and validates the nested memory snapshot and provenance chain.

## Read-only staging

Staging reports incoming/shared record, version, and event identities; same-ID/different-content collisions; incoming parent edges; source and destination history tips; divergent shared-record histories; and incoming/shared directional conflict annotations.

Staging does not mutate either instance. Same record identity with divergent version membership is treated as expected history divergence, not a collision. Conflicting content under the same version identity or conflicting event data under the same event identity is a hard collision.

## Explicit apply

`apply_exchange(envelope, destination, authorized=True)` is a distinct operation. The default is unauthorized; only the exact boolean value `True` permits the apply path. Apply refuses identity collisions and incomplete parent history.

The operation constructs and validates a candidate memory graph and candidate provenance ledger before adopting either into the destination. It unions version histories in deterministic parent-first order, preserves directional conflict annotations, and retains divergent branches rather than selecting a winner.

The source provenance ledger is not concatenated into the destination ledger. Instead, the destination appends one local, hash-linked `record.imported` receipt containing the complete original source provenance snapshot, source identity, export timestamp, envelope digest, and source record/version identities. Original source sequence numbers and hash links remain intact inside that receipt.

The receipt event ID is derived from the envelope digest. Reapplying the same envelope to the same destination is idempotent: it returns `already_applied` without changing the destination. If candidate construction or receipt validation fails, the live destination remains unchanged.

## Identity and trust boundaries

- Record IDs, version IDs, parent relationships, directional conflict annotations, event IDs, and original event streams are preserved as source evidence.
- A matching digest establishes canonical content equality, not authorship, actor identity, custody, or trustworthiness.
- Source instance IDs and actor IDs are caller-supplied, unauthenticated claims.
- SHA-256 is not a digital signature; an attacker able to replace both payload and digest can forge a new envelope.
- This is local file/API exchange, not network synchronization.
- No encryption, authentication, remote trust, automatic backup, distributed consensus, or concurrent-writer coordination is implemented.
- The in-memory apply transaction assumes the existing single-writer model; it is not a multiwriter locking protocol.
- The destination receipt embeds the complete source provenance ledger. This preserves evidence but can increase destination snapshot size.
- Transfer files and receipts may contain sensitive user content; nothing is uploaded or transmitted automatically.

## Test coverage added

The exchange tests cover detached export, inspection round-trip, directional conflict preservation, read-only staging, repeated staging, version identity collisions, divergent branches, explicit authorization, failure-before-adoption, successful apply, source provenance preservation, idempotent repeat apply, collision refusal, tampering, and timezone-qualified timestamps.

CI must pass on the exact latest branch head before this milestone is called tested or verified.
