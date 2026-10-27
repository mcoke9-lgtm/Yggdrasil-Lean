# Durable History Decision Record 001

**Status:** Provisional design baseline; not yet adopted as a project-wide invariant  
**Scope:** Minimum contract choices needed to draft backend-neutral adapter outcome types  
**Supersedes:** None. This record narrows options in `DURABLE_HISTORY_DECISION_OPTIONS_001.md` for the next design iteration; it does not erase alternatives.  
**Governing rule:** A new conversation does not change the active project branch. No choice here implies a backend or durable implementation exists.

## Provisional decisions

### DCR-1 — Event identity and idempotency

**Provisional choice:** Caller-supplied stable event ID, with project-scoped event-ID uniqueness and project-scoped idempotency keys.

- The caller creates the event ID before the first append and reuses it when retrying or reconciling an uncertain result.
- An event ID already bound to a different logical event payload conflicts.
- An idempotency key already bound to a different logical payload conflicts.
- A repeat of the same key and same logical payload resolves to the existing event and must not append a duplicate.
- Logical equality compares every schema-defined immutable event field. Commit receipts and mutable adapter-operation status are excluded unless separately modeled as event data.
- ID generation, collision resistance, and the exact canonical equality representation remain open.

**Reason:** The operation has a stable identity even when the caller cannot tell whether a commit succeeded. Project scope is explicit and does not depend on the storage backend.

### DCR-2 — Fork-event placement

**Provisional choice:** Record the canonical fork event in the destination branch history as its first event. Include a source anchor in the fork payload. Do not require an atomic write to both source and destination histories.

- The event identifies source and destination project/branch IDs.
- It records the source sequence and/or stable source event ID needed to identify the exact fork point.
- The destination history makes its ancestry explicit without requiring the source history to be modified.
- If the source anchor cannot later be resolved, verification must report an incomplete/unavailable lineage condition; it must not silently treat lineage as verified.
- A conversation-ID change alone never creates a fork event.

**Reason:** Destination-local history is easier to reason about without a cross-history atomic transaction. This choice favors explicit lineage and independent destination sequencing over a source-side audit entry. Whether source-side observability is separately needed remains open.

### DCR-3 — Per-branch ordering and concurrent writers

**Provisional choice:** Expected-position compare-and-append, enforced atomically by the adapter.

- The caller supplies the expected current branch head or sequence.
- If the expected position is stale, the adapter returns a conflict; the caller rereads and reassesses instead of silently appending against stale state.
- Sequence positions are per branch and unique.
- The adapter must not claim atomic compare-and-append if its backend cannot enforce it.
- History pagination must document whether it reads a stable snapshot or can observe later appends.
- Exact sequence numbering (zero-based versus one-based), head representation, and cursor semantics remain open.

**Reason:** Stale derivations become visible and reviewable. The model's current in-memory append order is not itself proof of concurrent-write safety.

### DCR-4 — Integrity milestone

**Provisional choice:** Do not claim digest-based integrity verification in the first adapter milestone. Represent integrity verification as unsupported until canonical serialization and threat model are specified.

- Preserve schema version and all defined immutable event fields.
- Distinguish unsupported schema, incomplete lineage, corrupt record, and unavailable verification.
- Do not use a successful Lean proof or CI run as evidence of storage integrity.
- Before adding a digest, specify canonical field ordering, text normalization, absent versus empty values, numeric representation, migration rules, digest scope, and threat model.

**Reason:** An underspecified digest can create false confidence. Identity, append/reconcile behavior, ordering, and schema handling can be designed without pretending canonicalization has been solved.

## Consequences for the next adapter-model draft

The next draft may define backend-neutral outcome and read-result types around these provisional choices. It must preserve at least these distinctions:

- Append: committed with receipt; already committed with receipt; conflict; unavailable; corrupt; commit outcome unknown.
- Read by identity: found; not found; unsupported schema; corrupt; unavailable.
- Ordered history: page; empty; gap/conflict; unsupported schema; unavailable.
- Reconciliation: committed; not committed; still unknown; conflict; corrupt; unavailable.
- Verification: valid only when supported checks actually pass; invalid; incomplete; unsupported schema/check; unavailable.

These are logical result distinctions, not claims that a concrete adapter exists. Do not encode receipts as mutable fields in immutable event payloads. Do not collapse unknown commit status into failure or success.

## Explicitly unresolved

1. Event-ID generation and collision handling.
2. Exact logical payload equality and future schema evolution.
3. Fork source anchor format and whether source-side fork observability is additionally required.
4. Sequence base, head representation, and cursor/snapshot semantics.
5. Canonical serialization, digest algorithm, and threat model.
6. Backend choice, durability boundary, transaction model, backup/restore, retention, access, deletion, and integration testing.

## Six-Part Integrity Check

1. **Count:** Exactly six lifecycle/result categories are specified in the next-draft plan: append, read-by-identity, ordered history, reconciliation, verification, and explicit fork-lineage handling.
2. **Role map:** Append records; identity reads locate one record; ordered history enumerates records; reconciliation resolves uncertain commits; verification evaluates integrity/lineage; fork-lineage handling preserves branch ancestry.
3. **Completeness:** The main contract boundaries are covered; backend and canonicalization details remain deliberately open.
4. **Status:** All four DCR choices are provisional design decisions. No adapter code, durable persistence, or integration test is claimed.
5. **Recheck:** The choices are consistent with the earlier gap review's evidence boundary and preserve distinct uncertain/corrupt/unavailable outcomes.
6. **Persistence and uncertainty:** This decision record is a new repository artifact; its existence must be verified by retrieving it after commit. CI validates repository workflow only, not runtime persistence semantics.
