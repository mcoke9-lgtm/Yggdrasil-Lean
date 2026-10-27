# Review History Adapter Implementation-Gate Decision Matrix 001

**Status:** Working decision register; recommendations are not adopted unless explicitly marked adopted.  
**Parent records:** [Adapter Contract 001](REVIEW_HISTORY_ADAPTER_CONTRACT_001.md), [Integration-Test Specification 001](REVIEW_HISTORY_ADAPTER_INTEGRATION_TEST_SPEC_001.md), [Identity-Read Boundary 001](IDENTITY_READ_CHECKED_CLASSIFICATION_BOUNDARY_001.md).  
**Backend decision:** [Android-Mobile-First Backend Decision 001](REVIEW_HISTORY_ADAPTER_ANDROID_MOBILE_BACKEND_DECISION_001.md).  
**Branch:** `review-history-identity-read-evidence-001`.

## Purpose

Prevent implementation from quietly turning open assumptions into adopted architecture. This register distinguishes confirmed project constraints, provisional choices, and unresolved gates. It does not claim an adapter exists.

## Decision register

| ID | Decision area | Current status | Recommendation / next evidence |
|---|---|---|---|
| D-1 | Persistence backend | **Provisionally chosen: device-local SQLite for Android-mobile-first use** | Implement only through a demonstrated Android host/runtime path. Revisit if multi-device shared history becomes a requirement. |
| D-2 | Adapter implementation | **Open** | No durable adapter exists yet. Keep Lean's pure model separate from I/O; establish Android host and Lean integration path. |
| D-3 | Identity-read attestation | **Open** | Derive completeness from actual adapter-controlled query/pagination/permission behavior; never accept caller-supplied Bool flags as authorization. |
| D-4 | Append commit boundary | **Open** | Define what the selected SQLite configuration guarantees after success, timeout, process crash, and restart. A successful call or queued write alone is not durable commit evidence. |
| D-5 | Concurrency / expected position | **Proposed requirement** | Use database transactions and constraints to enforce compare-and-append atomically; verify with competing/concurrent clients in IT-2. |
| D-6 | Idempotency scope | **Provisional in existing reference rules** | Project-scoped idempotency is a candidate policy, not an adopted backend guarantee. Confirm uniqueness enforcement and payload-equivalence rules before integration. |
| D-7 | Event-ID creation | **Open** | Existing contract permits caller-supplied stable IDs as a candidate; uniqueness, collision handling, and ownership remain to be specified. |
| D-8 | Logical payload equality | **Open** | Define schema-level equality/canonicalization before relying on retry equivalence. Raw string equality in the reference classifier is not a final policy. |
| D-9 | Ordered history and pagination | **Open** | Specify stable ordering, cursor lifetime, and snapshot consistency. Do not infer snapshot consistency from a complete traversal. |
| D-10 | Fork representation | **Provisional** | Destination-recorded fork with explicit source anchor is a candidate; source-side visibility and exact anchor semantics remain open. Missing anchors must remain explicit. |
| D-11 | Integrity / digest | **Open** | Make no cryptographic digest claim until canonical encoding, threat model, and digest/verification rules are reviewed and implemented. |
| D-12 | Integration release gate | **Proposed requirement** | Run IT-1 through IT-6 against the concrete Android-hosted SQLite adapter; record versions and outcomes, including skipped or failed cases, before claiming integration verification. |
| D-13 | Public outcome constructors | **Known limitation** | They remain freely constructible in the reference API. Do not claim they enforce trust. Review API/module visibility only with a concrete adapter design. |

## Backend selection criteria and current fit

The current target is Android mobile, with durable offline history on one device. Device-local SQLite is provisionally selected because it is available on Android and supports transactions. This is a target choice, not a claim that the repository has a working SQLite binding or adapter.

The remaining gates are:
1. Demonstrate the Android host and Lean integration boundary.
2. Prove atomic append, expected-position enforcement, and safe retry after uncertain commit.
3. Define and verify durability settings and restart/recovery behavior.
4. Establish complete identity lookup semantics and trusted attestation.
5. Define pagination/snapshot behavior, backup/export, migration, and failure reporting.
6. Run IT-1 through IT-6 against the actual adapter.

No remote database or cross-device synchronization is selected. If multi-device shared history becomes a real requirement, reopen D-1 rather than treating a local SQLite file as shared storage.

## Six-Part Integrity Check

1. **Count:** thirteen decision areas, D-1 through D-13.
2. **Roles:** backend, adapter, read attestation, commit, concurrency, idempotency, IDs, payload equality, pagination, forks, integrity, integration gate, API visibility.
3. **Completeness:** mobile-first backend target is selected; Android host/integration and backend guarantees remain unresolved.
4. **Status:** D-1 is provisional; other statuses remain as listed; no production adapter is adopted.
5. **Recheck:** the decision is local-first and reversible; no durability, concurrency, cryptographic, or backend-completeness claim is made.
6. **Persistence:** decision register is documentation only. It does not change Lean declarations or prove integration tests. Next action: establish the Android host/integration path and implement a minimal SQLite adapter before claiming runtime verification.
