# Durable History Decision Options 001

**Status:** Proposed options for review; no choices adopted  
**Scope:** Event identity, fork-event placement, ordering/concurrency, and canonical integrity boundary  
**Parent review:** `docs/ADAPTER_MODEL_GAP_REVIEW_001.md`  
**Purpose:** Make unresolved choices explicit before designing a concrete adapter API. Preserve backend neutrality and do not imply persistence guarantees that are not implemented.

## Governing invariants

1. A conversation switch alone is not a project-branch change and must not create a fork event.
2. A transformation may change configuration, but must preserve identity and provenance of parts, record changed relationships, and disclose unrecoverable information.
3. Same idempotency key plus the same logical payload must not create a duplicate; the same key bound to a different logical payload must conflict.
4. A caller must be able to distinguish confirmed commit, confirmed non-commit, conflict, corruption, unavailability, and unresolved commit outcome.
5. Commit receipts and adapter status are operation evidence, not silently mutable fields of an immutable event payload.
6. Options below are proposals for deliberation, not adopted requirements unless explicitly accepted later.

## Decision A — Event identity and idempotency scope

### Options

- **A1 — Caller-supplied stable event ID; project-scoped idempotency key.** The caller creates an event ID before append and reuses it for retries. Event-ID uniqueness is enforced across the project; idempotency keys are unique within a project. A key is permanently bound to one logical payload.
- **A2 — Adapter-generated event ID; caller-supplied idempotency key.** The adapter allocates the event ID on first append and must return the same identity when a retry is reconciled. This requires a durable idempotency mapping to survive process restarts.
- **A3 — Caller-supplied stable event ID and branch-scoped idempotency key.** Similar to A1, but key scope is a single branch. Cross-branch reuse is allowed, so the adapter must not mistake it for one logical operation across branches.

### Proposed default for evaluation

Evaluate **A1** first because the operation has a stable identity before an uncertain commit and the uniqueness scope is not tied to a particular backend. Do not adopt it yet. The contract must still specify how IDs are generated, how collisions are handled, and whether event IDs are globally unique or only project-unique. The logical payload comparison must include every schema-defined event field and exclude operation receipts/status unless explicitly modeled as a separate linked record.

### Acceptance questions

- Can a retry after process restart identify the exact attempted event?
- Does reuse of an event ID with a different payload conflict?
- Is idempotency scope stated in every adapter implementation and test?

## Decision B — Fork-event placement and lineage

### Options

- **B1 — Destination history only.** A fork event is the first event in the new branch and names the source project/branch and source position. Source history remains unchanged.
- **B2 — Source history only.** The source records the fork, while the destination begins with a link to that source event. This creates a dependency on the source history remaining readable.
- **B3 — Both histories.** The source records the act of forking and the destination records its initial lineage event. The contract must define whether these are one logical event with two placements or two linked events, and how partial success is reconciled.
- **B4 — One canonical fork event plus a destination genesis link.** The canonical event is placed in one designated history; the destination has a durable link/marker to it. The location and link semantics must be explicit.

### Proposed default for evaluation

Evaluate **B4** before B3. It may preserve one canonical event identity while making the destination's ancestry discoverable, without silently requiring a two-history atomic transaction. This is only an option, not a conclusion: it is unsuitable if the system requires each branch's history to be independently complete without resolving a cross-branch link.

### Required fork fields / behavior to settle

- Source project and branch IDs plus the exact source sequence or event anchor.
- Destination project and branch IDs.
- Canonical event identity and explicit history placement.
- Destination's initial lineage link and behavior if the source link is missing or unreadable.
- Sequence allocation in the destination and retry behavior if the operation's outcome is unknown.
- No implicit fork event from changing `conversationId`.

## Decision C — Sequence allocation and concurrent writers

### Options

- **C1 — Adapter-serialized per-branch append.** The adapter assigns the next sequence atomically; competing writers are serialized.
- **C2 — Expected-position compare-and-append.** The caller supplies the expected current sequence/head. A stale expectation returns a conflict and requires rereading/reassessment.
- **C3 — Caller-assigned sequence with uniqueness constraints.** The adapter rejects duplicate or out-of-order sequence values. This shifts more coordination responsibility to callers and is unsafe without a clear conflict protocol.

### Proposed default for evaluation

Evaluate **C2**, with the adapter enforcing atomic compare-and-append. It makes stale state visible instead of silently accepting an append derived from an obsolete branch head. The API should not require C2 if a selected backend cannot implement it correctly; that backend must report its limitation rather than simulate atomicity.

### Required guarantees to specify

- Sequence scope is per branch.
- Exactly one event can occupy a given sequence position.
- A conflicting concurrent append returns a conflict or a distinct retryable result; it cannot silently overwrite history.
- Pagination and history reads state whether they are a stable snapshot or may observe later appends.
- Gaps, duplicate positions, and inconsistent parent links remain visible as integrity findings.

## Decision D — Canonical representation and integrity

### Options

- **D1 — Canonical serialized event payload with digest.** Define a canonical encoding and digest over the immutable event payload only. Keep commit receipts and mutable adapter status outside that digest.
- **D2 — Digest over a versioned canonical envelope.** Include schema version and immutable event payload in the digest, with explicit rules for fields introduced by future schema versions.
- **D3 — No digest in the first adapter milestone.** Implement identity, append/reconcile, ordering, and schema-version handling first; mark integrity verification as unsupported rather than claiming validation.

### Proposed default for evaluation

Treat canonicalization as a prerequisite to any digest claim, but do not prematurely select an algorithm or threat model. If encoding rules cannot yet be specified and tested, **D3** is safer than an underspecified hash. If a digest is introduced, choose D1 or D2 only after field ordering, string normalization, absent-versus-empty values, numeric forms, and schema migration behavior are explicit.

### Integrity boundary

A digest can detect some accidental or unauthorized changes only under a defined encoding and threat model. A digest alone does not prove origin, append order, durable commit, or trustworthy storage. Do not label a record `Valid` when its schema is unsupported or required lineage is unavailable; use the contract's distinct incomplete/unsupported/unavailable outcomes.

## Dependency order

1. Resolve A (identity and idempotency scope).
2. Resolve B (fork lineage and placement), using the identity decision.
3. Resolve C (per-branch sequence and concurrency), including fork destination genesis behavior.
4. Scope D (canonical representation and integrity) before implementing any digest or claiming verification.
5. Only then draft the backend-neutral Lean outcome/API types and tests.
6. Select a backend only after the logical contract is stable enough to test against backend capabilities.

## Six-Part Integrity Check

Exactly six parts in the dependency plan are present: A, B, C, D, adapter outcome/API, and backend selection. Each is mapped to a distinct role. The four design areas remain proposed; no option is adopted by this document. This file records a proposal, not an implementation or test result. It was written to be reversible: a later decision record can select, amend, or reject any option without erasing the alternatives.
