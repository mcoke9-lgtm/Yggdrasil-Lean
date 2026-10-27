# Adapter Deployment and Access Requirements 001

**Status:** requirements register; unresolved, not an architecture decision  
**Scope:** Review History Adapter in Yggdrasil-Lean  
**Branch:** `review-history-identity-read-evidence-001`

## Purpose

Record the deployment facts needed to compare persistence backends without selecting a technology by assumption. The Lean outcome types and identity-read classifier are reference models; they are not an operational service or a durable adapter.

## Requirements that must be established

| ID | Requirement | Current status | Why it matters |
|---|---|---|---|
| DEP-1 | Runtime that hosts the adapter (CLI, long-running service, CI job, desktop/local process, or other) | Open | Constrains lifecycle, dependencies, and available storage. |
| DEP-2 | Number and location of writers/readers; whether separate processes or machines must share one history | Open | Determines whether local-only storage is sufficient and what coordination is required. |
| DEP-3 | Durability expectation after process exit, machine restart, or power loss | Open | Defines the promised persistence boundary and recovery behavior. |
| DEP-4 | Required atomicity for append and expected-position checks under concurrent writers | Requirement identified; exact contract open | A read-then-write sequence without an atomic commit boundary can admit races. |
| DEP-5 | History size, retention, and expected query/pagination patterns | Open | Affects storage layout, indexing, and bounded reads. |
| DEP-6 | Backup, portability, migration, and operator recovery requirements | Open | Determines whether a backend can be maintained and recovered in practice. |
| DEP-7 | Access-control and trust boundary: who may write, read, repair, or declare an identity lookup complete | Open | A storage result alone does not establish trusted completeness or provenance. |
| DEP-8 | Offline/network constraints and acceptable service dependencies | Open | Determines whether a remote/shared backend is feasible. |
| DEP-9 | Failure reporting and observability requirements | Partially specified | Must preserve distinctions among confirmed absence, incomplete lookup, unavailable storage, and uncertain commit. |

## Non-negotiable behavioral gates

Before a backend is accepted, demonstrate all of the following against the actual implementation:

1. An uncertain append result is not treated as a confirmed failure; retry is safe only with duplicate-prevention evidence.
2. Concurrent appends enforce the chosen expected-position rule atomically.
3. Reuse of an event ID or idempotency key with different content is detected according to the adopted equality rule.
4. Ordered reads, pagination, and recovery preserve the declared consistency contract.
5. Forks preserve an explicit source anchor and destination-side lineage.
6. Corruption, unsupported schema, incomplete reads, and unavailability remain distinguishable.

These are acceptance gates, not evidence that an adapter or backend currently passes them. Integration test groups IT-1 through IT-6 remain specified but unexecuted until a real adapter/backend exists.

## Backend evaluation rule

Do not select SQLite, filesystem files, or a remote database until DEP-1 through DEP-3 and DEP-7 through DEP-8 are resolved. Then compare feasible candidates against atomicity, durability, complete-read semantics, pagination/snapshot consistency, recovery/portability, failure semantics, and operational burden. Record evidence for each score; unknowns must remain unknown rather than being scored as passes.

## Current conclusion

- **Verified from project context:** no operational adapter or durable storage integration is present in the current design; the deployment environment and shared-access requirements have not been established.
- **Proposed:** use this register as the prerequisite gate for backend selection.
- **Not adopted:** no backend, deployment topology, or production trust boundary.
- **Not tested:** no real-backend integration tests.
- **Next action:** establish DEP-1 through DEP-3 and DEP-7 through DEP-8 from the intended use case, then score only compatible backend candidates.
