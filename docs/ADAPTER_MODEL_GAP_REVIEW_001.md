# Adapter-to-Model Gap Review 001

**Status:** Review finding; proposed, not adopted  
**Scope:** Cross-check of the durable-history specification, persisted-record schema, adapter contract, and current Lean model  
**Reviewed branch:** `main`  
**Reviewed model files:** `BranchContinuity.lean`, `BranchForking.lean`, `MultiStepHistory.lean`, `PropagationHistory.lean`, `SelectivePropagation.lean`

## 1. Finding

The current Lean model provides immutable in-memory transition values, tracked state, explicit branch-fork construction, named route snapshots, assessments, and ordered in-memory histories. It does not define the durable event envelope or adapter semantics. The three specification documents now describe much of that target contract, but several design decisions must be resolved before an adapter API can be specified without inventing behavior.

## 2. Six-part gap register

| # | Role | Current model evidence | Missing decision or support | Status |
|---|---|---|---|---|
| 1 | Event identity and scope | `ProjectState` has project/branch IDs; review entries have no stable event ID | Define event-ID generation and uniqueness scope; define idempotency-key scope and binding to logical payload | Open |
| 2 | Fork lineage and placement | `forkProject` assigns a caller-supplied branch ID and carries the baseline state; no fork event exists | Define whether fork is recorded in source history, destination history, or both; specify parent/fork links and sequence assignment | Open |
| 3 | Sequence and concurrency | In-memory lists preserve append order | Define per-branch sequence allocation, concurrent-writer serialization or conflict policy, and whether stale expected positions are supported | Open |
| 4 | Payload versus commit evidence | Logical review values contain transition, snapshot and assessment; no commit receipt exists in Lean | Keep receipt/status as adapter operation metadata or a separately linked record; define the exact commit boundary before claiming durable success | Contract clarified; backend open |
| 5 | Integrity and schema evolution | Lean derives equality for in-memory structures; no serialized representation or digest exists | Define canonical encoding, missing/unavailable semantics in encoding, digest scope/threat model, and migration fidelity before claiming integrity verification | Blocked |
| 6 | Recovery provenance and lifecycle | Reconstruction and reassessment are pure value operations; history is in-memory | Persist original assessments separately from reconstructions/reassessments; define how unsupported versions, missing references, retention and restore findings are surfaced | Contract specified; implementation absent |

## 3. Evidence boundary

- **Verified from retrieved source:** the model separates conversation location from project branch; explicit fork construction preserves project ID, baseline version, and tracked lists; route assessment retains its named snapshot; in-memory append operations preserve list order.
- **Not established by these sources:** durable persistence, stable event IDs, idempotent writes, atomic commit, concurrent writer behavior, restart recovery, cryptographic integrity, migration fidelity, or integration acceptance.
- **CI:** successful CI validates the repository workflow for its commit; it does not establish the unimplemented persistence guarantees above.

## 4. Safe next implementation boundary

Do not select a backend or write a concrete adapter signature until decisions 1–3 are resolved, and the canonical representation/integrity decision in item 5 is explicitly scoped. A backend-neutral Lean outcome type can be drafted after those choices; it must preserve the contract's distinctions among committed, already committed, conflict, unavailable, corrupt, commit unknown, and unsupported/incomplete reads.

## 5. Six-Part Integrity Check

Exactly six roles are present and mapped one-to-one to the six gap-register rows. All are review findings, not adopted decisions. No adapter implementation or integration test is claimed.
