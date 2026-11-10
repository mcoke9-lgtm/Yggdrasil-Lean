# YIM-001 audited memory operations

The audited facade connects selected memory mutations to the provenance event ledger. It is an additive layer; the underlying MRP-001 Revision 4 contract and Core Memory snapshot format are unchanged.

## Current API

```python
from yim001 import AuditedMemoryStore, save_audited, load_audited

memory = AuditedMemoryStore("local-node")
first = memory.create_record({"hello": "world"})
second = memory.revise(first.record_id, {"hello": "again"})
memory.annotate_conflict(first.version_id, second.version_id)

save_audited(memory, "audited-memory.json")
restored = load_audited("audited-memory.json")
```

Each successful create, revise, or conflict-annotation operation appends exactly one corresponding event. The wrapper constructs candidate copies of both the memory store and ledger, then adopts both only after the memory operation and event append succeed. A failed operation therefore leaves the live pair unchanged.

The combined snapshot stores the existing Core Memory snapshot and provenance ledger as separate nested objects in one JSON file. It is written using the existing atomic JSON replacement helper. This avoids two independently replaced files drifting apart during a normal save.

## Boundaries

- The facade exposes read operations and audited create/revise/conflict mutations; it does not expose the mutable underlying MemoryStore.
- The actor ID is caller-supplied and unauthenticated. It is an attribution claim, not proof of identity.
- Hash-linked events do not authenticate actions or prevent full-ledger rewrite and re-hashing.
- The combined file is local and single-writer. There is no concurrent-writer coordination, network exchange, device synchronization, or automatic backup.
- This first milestone does not yet automatically log import/export/recovery/reconciliation workflows.
- Atomic replacement reduces partial-file risk but is not a guarantee against every power-loss, hardware, or filesystem failure.
- This additive audited snapshot has its own format/version; it does not revise MRP-001 or the underlying Core Memory snapshot format.
