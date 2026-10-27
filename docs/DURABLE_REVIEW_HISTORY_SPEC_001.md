# Durable Review History Specification 001

**Status:** Proposed contract; not an implementation or adoption decision  
**Scope:** Durable storage and recovery for versioned transition-review history in Yggdrasil-Lean  
**Related implementation:** `YggdrasilLean/PropagationHistory.lean`  
**Current boundary:** The Lean structures and proofs model in-memory values. They do not establish that records survive process exit, storage failure, or deployment changes.

## 1. Purpose

Define the minimum contract a future persistence adapter must satisfy so that a transition's identity, provenance, historical assessment, and rule snapshot remain recoverable across process and conversation boundaries.

Governing invariant:

> A transformation may change a structure's configuration, but must preserve identity and provenance of parts, record changes to relationships, and disclose unrecoverable information.

A conversation switch alone must not change the active project or branch. A branch change requires an explicit fork or recorded transition.

## 2. Non-goals and decisions left open

This document does not select a database, file format, cloud provider, serialization library, or deployment model. It does not claim that durable storage exists. It does not define a universal timestamp authority or assume that a caller-supplied rule-set identifier is globally unique.

Backend selection follows the contract and acceptance tests, not the reverse.

## 3. Record contract

A persisted review event MUST preserve, directly or through immutable content-addressed references:

- **Schema identity:** schema name and version, plus an explicit migration path or an unsupported-version result.
- **Event identity:** stable event ID, project ID, branch ID, event sequence/order, and event kind (original assessment, reassessment, fork, or other explicitly defined event).
- **Transition provenance:** the original transition record, including prior and resulting project/branch identity and tracked before/after state or stable references to it.
- **Derived change set:** the fields compared and the resulting change set, or enough immutable input to reproduce it with a versioned derivation method.
- **Rule snapshot:** the exact routes and rule configuration used for that assessment, not merely a mutable label such as `rules-v1`.
- **Assessment result:** original route decisions, trigger fields, selection flags, and decision order as actually assessed.
- **Lineage:** parent/prior event reference where applicable, and a link to the original event for reassessments.
- **Integrity metadata:** a verifiable digest or equivalent integrity mechanism over a canonical representation, with algorithm/version identified.
- **Lifecycle evidence:** storage outcome and any available commit/receipt identifier must be associated with the event. Commit receipts and adapter-generated status are operation metadata by default, not part of the immutable event payload or its payload digest; if retained durably, their relationship to the event must be explicit. Wall-clock timestamps may be recorded, but must not be the sole ordering authority.

If a field is unavailable, the record must mark it as unavailable and explain why; it must not silently fabricate a value.

## 4. Append and mutation semantics

1. **Append-only history.** A committed historical event is immutable. Corrections and reassessments append new events linked to the prior event; they do not overwrite the original assessment.
2. **Atomic visibility.** Readers see either the complete committed event or no committed event. Partial writes must be detectable and must not appear as valid history.
3. **Idempotency.** Retrying an append with the same idempotency key and same payload returns the existing outcome without duplicating the event. Reusing that key with a different payload is a conflict that must be surfaced.
4. **Ordering.** The adapter must provide a stable order per project/branch history. Concurrent appends must either receive a deterministic committed order or return an explicit conflict requiring resolution.
5. **Failure reporting.** Timeouts, permission failures, full storage, integrity failures, and uncertain commit outcomes must be distinguishable where the backend permits. An uncertain outcome must be reconciled by reading the idempotency key/event ID before retrying.
6. **No false success.** An in-memory append, queued write, or successful serialization is not proof of durable persistence. Report durable success only after the adapter's defined commit boundary is confirmed.

## 5. Read, recovery, and reassessment

- A restart recovery test must load history using only durable storage, with no reliance on the process's prior memory.
- Retrieval must preserve event identity, order, transition provenance, exact historical rule snapshot, and the original assessment decisions.
- Reconstruction from stored inputs is a separate operation from retrieval of the originally stored assessment.
- Reassessment under new rules creates a new event that references the original. It must not relabel the old assessment as if it had been produced by the new rules.
- Unknown schema versions, missing referenced objects, invalid digests, sequence gaps, duplicate conflicting IDs, and truncated records must produce explicit integrity/recovery findings.
- If reconstruction is possible but the original assessment is missing, report that distinction. A reconstructed result must never be presented as the historical original.
- Recovery must preserve project and branch identity. A branch change must be explicit and auditable.

## 6. Acceptance tests

A persistence adapter is not accepted until each test is executed against the real adapter and its target storage. Lean proofs alone do not satisfy integration or restart tests.

1. **Identity and provenance:** round-trip a transition and verify project/branch identity, parent references, tracked state, and change set.
2. **Rule and assessment fidelity:** round-trip the exact rule snapshot and original decision list; verify route order, trigger fields, and selection results remain unchanged.
3. **Atomic and idempotent append:** simulate retry, same-key/same-payload, same-key/different-payload, interrupted write, and uncertain commit.
4. **Ordering and restart recovery:** append multiple events, terminate the process, start a fresh process, and verify stable order and complete recovery.
5. **Integrity and visible failure:** test altered bytes, missing references, unknown schema version, truncated data, permission denial, and storage exhaustion; verify no silent success or false reconstruction.
6. **Original versus reassessment:** append an assessment under snapshot A, reassess under snapshot B, restart, and verify both events remain distinct, linked, and unchanged.

Each test result must be labelled **proposed**, **executed**, **passed**, **failed**, or **blocked**. “Passed” requires recorded evidence from the actual test run; a test that was only specified is not passed.

## 7. Six-Part Integrity Check

| # | Role | Required evidence | Current status |
|---|---|---|---|
| 1 | Identity and provenance | Stable event/project/branch identity and lineage | Proposed |
| 2 | Rule snapshot fidelity | Exact configuration and original assessment retained | Proposed |
| 3 | Atomicity and idempotency | Duplicate/retry/interrupted-write evidence | Proposed |
| 4 | Ordering and recovery | Real restart round-trip evidence | Proposed |
| 5 | Integrity and failure visibility | Corruption and failure-path evidence | Proposed |
| 6 | Reassessment separation | Original and new assessment coexist without overwrite | Proposed |

**Count check:** exactly six acceptance roles; no role is represented as implemented or verified by this specification.

## 8. Implementation sequence

1. Review this contract against the current Lean data structures and identify fields that are not yet represented.
2. Define a serialization-neutral persisted-record schema and canonical encoding rules.
3. Select a backend only after the adapter contract and failure model are reviewed.
4. Implement the adapter with explicit commit, conflict, and recovery results.
5. Execute the six acceptance tests against the actual backend, including a process restart.
6. Update the branch registry and status record only after evidence is retrieved and reviewed.

## 9. Open questions

- Which storage backend and deployment boundary are appropriate?
- Should state snapshots be embedded, content-addressed, or both?
- Which digest algorithm and canonical serialization format will be adopted?
- What concurrency model is required for simultaneous writers?
- What retention, backup, access-control, and deletion policy applies?
- Which migration policy governs older records when a schema or derivation algorithm changes?

These remain unresolved. No persistence implementation or adoption decision is implied.
