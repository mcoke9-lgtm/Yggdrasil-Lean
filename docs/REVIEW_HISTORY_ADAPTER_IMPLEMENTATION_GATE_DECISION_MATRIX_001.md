# Review History Adapter Implementation-Gate Decision Matrix 001

**Status:** Working decision register; recommendations are not adopted unless explicitly marked adopted.  
**Parent records:** [Adapter Contract 001](REVIEW_HISTORY_ADAPTER_CONTRACT_001.md), [Integration-Test Specification 001](REVIEW_HISTORY_ADAPTER_INTEGRATION_TEST_SPEC_001.md), [Identity-Read Boundary 001](IDENTITY_READ_CHECKED_CLASSIFICATION_BOUNDARY_001.md).  
**Branch:** `review-history-identity-read-evidence-001`.

## Purpose

Prevent implementation from quietly turning open assumptions into adopted architecture. This register distinguishes confirmed project constraints, recommended next steps, and choices that remain unresolved. It does not select a backend or claim an adapter exists.

## Decision register

| ID | Decision area | Current status | Recommendation / next evidence |
|---|---|---|---|
| D-1 | Persistence backend | **Open** | Compare candidates against deployment, durability, concurrency, portability, and recovery requirements before selecting one. Do not infer a choice from the Lean repository. |
| D-2 | Adapter implementation | **Open** | Implement only after D-1 and the backend's real guarantees are documented. Keep Lean's pure model separate from I/O. |
| D-3 | Identity-read attestation | **Open** | Derive completeness from actual adapter-controlled query/pagination/permission behavior; never accept caller-supplied Bool flags as authorization. |
| D-4 | Append commit boundary | **Open** | Define what the chosen backend guarantees after success, timeout, process crash, and restart. A successful call or queued write alone is not durable commit evidence. |
| D-5 | Concurrency / expected position | **Proposed requirement** | Use a backend-supported atomic compare-and-append or transaction equivalent; verify with genuinely concurrent clients in IT-2. Specific mechanism depends on D-1. |
| D-6 | Idempotency scope | **Provisional in existing reference rules** | Project-scoped idempotency is a candidate policy, not an adopted backend guarantee. Confirm uniqueness enforcement and payload-equivalence rules before integration. |
| D-7 | Event-ID creation | **Open** | Existing contract permits caller-supplied stable IDs as a candidate; uniqueness, collision handling, and ownership remain to be specified. |
| D-8 | Logical payload equality | **Open** | Define schema-level equality/canonicalization before relying on retry equivalence. Raw string equality in the reference classifier is not a final policy. |
| D-9 | Ordered history and pagination | **Open** | Specify stable ordering, cursor lifetime, and snapshot consistency. Do not infer snapshot consistency from a complete traversal. |
| D-10 | Fork representation | **Provisional** | Destination-recorded fork with explicit source anchor is a candidate; source-side visibility and exact anchor semantics remain open. Missing anchors must remain explicit. |
| D-11 | Integrity / digest | **Open** | Make no cryptographic digest claim until canonical encoding, threat model, and digest/verification rules are reviewed and implemented. |
| D-12 | Integration release gate | **Proposed requirement** | Run IT-1 through IT-6 against the concrete adapter/backend; record versions and outcomes, including skipped or failed cases, before claiming integration verification. |
| D-13 | Public outcome constructors | **Known limitation** | They remain freely constructible in the reference API. Do not claim they enforce trust. Review API/module visibility only with a concrete adapter design. |

## Backend selection criteria (not a backend recommendation)

Any candidate should be evaluated against the same questions:

1. **Deployment:** where does it run, and which clients/processes must access the history?
2. **Atomicity:** can it enforce unique identities and compare-and-append atomically?
3. **Durability:** what survives process crash, host restart, power loss, and partial write?
4. **Read completeness:** can the adapter know whether the declared lookup scope was fully searched?
5. **Pagination and snapshots:** can ordered history be read consistently across pages?
6. **Recovery and portability:** can history be backed up, restored, inspected, and migrated without losing provenance?
7. **Failure semantics:** can timeout/uncertain commit be reconciled without creating duplicates?
8. **Operational burden:** what maintenance, access control, and deployment assumptions does it introduce?

No candidate has been scored because the required deployment and access constraints have not been established in the retrieved contract. Selecting SQLite, a filesystem layout, or a remote database now would therefore be an ungrounded architecture choice.

## Six-Part Integrity Check

1. **Count:** thirteen decision areas, D-1 through D-13.
2. **Roles:** backend, adapter, read attestation, commit, concurrency, idempotency, IDs, payload equality, pagination, forks, integrity, integration gate, API visibility.
3. **Completeness:** covers the major implementation gates in the parent contract; concrete deployment constraints and backend guarantees are still missing.
4. **Status:** one known API limitation; several proposed/provisional requirements; no backend selection or production adapter adopted.
5. **Recheck:** recommendations are explicitly distinguished from adopted decisions; no durability, concurrency, cryptographic, or backend-completeness claim is made.
6. **Persistence:** decision register is documentation only. It does not change Lean declarations or prove CI/build status. Next justified action is to establish the deployment/access requirements needed to evaluate backend candidates, then select one explicitly.
