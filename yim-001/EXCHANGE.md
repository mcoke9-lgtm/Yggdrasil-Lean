# YIM-001 cross-instance exchange — implementation contract

**Status:** Proposed implementation contract. This document does not claim that exchange is implemented.

## Goal

Allow two independent YIM-001 instances to exchange memory and provenance without silently overwriting either instance, changing existing identities, or discarding divergent histories.

## First exchange milestone: portable transfer envelope

An exported transfer envelope contains:

- envelope format identifier and integer format version;
- source instance identifier (a caller-supplied claim, not authenticated);
- export timestamp with an explicit UTC offset;
- the complete existing Core Memory snapshot;
- the complete provenance ledger snapshot;
- a canonical SHA-256 digest over the envelope payload.

The transfer is a detached copy. Export must not mutate the source instance. The existing Core Memory snapshot and MRP-001 Revision 4 remain unchanged.

## Import stages

Import is deliberately split into two operations:

1. **Inspect / validate (read-only):** validate the envelope schema, digest, memory snapshot, provenance chain, and internal references. Return a structured report. Do not mutate the destination.
2. **Stage for reconciliation (read-only):** compare the incoming records and event history with the destination. Produce a detached proposal describing new identities, identical existing identities, divergent identities, missing history, and conflicts. Do not commit changes.

A later, separately authorized operation may apply an explicitly selected reconciliation plan. The initial implementation must not silently merge or overwrite.

## Identity and history rules

- Record IDs, version IDs, event IDs, and existing directional conflict annotations are preserved exactly.
- Identical record/version identities with identical canonical content and relationships are recognized as shared evidence, not duplicated.
- The same identity with different content, metadata, parent relationships, or event digest is a hard identity collision and must be reported; neither side wins automatically.
- A version present only on one side is proposed as an addition, subject to parent/reference validation.
- Divergent histories remain distinct branches. Do not rewrite parent links to force a linear history.
- Directional conflict annotations retain source and target orientation.
- Event ledgers are independent streams. Do not concatenate them and invent a global order. Preserve each stream's source identity and local sequence. A future combined view may reference source ledger plus event ID.
- A matching digest establishes byte/content equality under the specified canonicalization; it does not establish authorship, actor identity, custody, or trustworthiness.

## Safety and failure behavior

- Malformed envelopes, invalid JSON values, duplicate keys, digest mismatch, invalid memory graphs, invalid event chains, and dangling references are rejected before staging.
- Staging is read-only and deterministic for the same inputs.
- An interrupted inspection/staging attempt leaves both source and destination unchanged.
- No network transport, authentication, encryption, remote trust, automatic backup, multi-writer locking, or conflict-resolution policy is implied by this milestone.
- Transfer files may contain sensitive user content; the implementation must not upload them or transmit them automatically.

## Acceptance tests

1. Export/import-inspect round-trip preserves all record, version, parent, conflict, event, and source-ledger identities.
2. Source instance snapshot is byte-for-byte/canonically unchanged by export.
3. Destination snapshot is unchanged by inspect and stage.
4. Re-importing the same envelope is idempotently classified as already present/shared evidence.
5. New incoming records and versions are identified without mutating the destination.
6. Divergent versions with shared ancestors are reported as branches; no parent rewriting occurs.
7. Same-ID/different-content and same-event-ID/different-digest collisions are explicit hard conflicts.
8. Missing parent, dangling conflict endpoint, invalid event chain, duplicate IDs, malformed digest, and altered payload are rejected.
9. Event streams from two sources remain distinct and preserve their own sequence/hash chains.
10. Directional conflict orientation survives transfer exactly.

## Deferred milestone

Only after the read-only exchange path is implemented and tested should we add an explicit reconciliation-plan format and a separately authorized apply operation. That later operation must be transactional across memory and provenance and must preserve the original source snapshots as evidence.
