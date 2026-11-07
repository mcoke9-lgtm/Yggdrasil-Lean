# Adapter Deployment and Access Requirements 001

**Status:** Android-mobile-first target recorded; detailed implementation requirements remain open.  
**Scope:** Review History Adapter in Yggdrasil-Lean  
**Backend decision:** [Android-Mobile-First Backend Decision 001](REVIEW_HISTORY_ADAPTER_ANDROID_MOBILE_BACKEND_DECISION_001.md)  
**Branch:** `review-history-identity-read-evidence-001`.

## Purpose

Record deployment facts needed to implement durable review history without confusing a provisional backend target with a working adapter. The Lean outcome types and identity-read classifier are reference models; they are not an operational service or durable storage adapter.

## Requirements and current status

| ID | Requirement | Current status | Why it matters |
|---|---|---|---|
| DEP-1 | Runtime that hosts the adapter | **Target: Android mobile; exact host/runtime bridge open** | Determines APIs, lifecycle, packaging, and storage access. |
| DEP-2 | Number and location of readers/writers | **Initial scope: one device-local history; cross-device sharing not selected** | SQLite is not to be treated as a multi-device shared database. |
| DEP-3 | Durability after exit/restart/power loss | **Target: durable local history; exact SQLite transaction/sync configuration and guarantees open** | Defines truthful commit and recovery behavior. |
| DEP-4 | Atomicity for append and expected-position checks | Requirement identified; transaction/schema design open | Read-then-write without atomic commit can admit races. |
| DEP-5 | History size, retention, query/pagination patterns | Open | Affects schema, indexes, and bounded reads. |
| DEP-6 | Backup, portability, migration, and recovery | Open | Determines how history can be inspected, restored, and upgraded. |
| DEP-7 | Access-control and trust boundary | Open | Storage alone does not establish trusted completeness or provenance. |
| DEP-8 | Offline/network constraints and service dependencies | **Target: offline-capable local-first; remote service not required for initial use** | Fits mobile continuity and avoids a network dependency. |
| DEP-9 | Failure reporting and observability | Partially specified | Must preserve absence, incomplete lookup, unavailable storage, and uncertain commit distinctions. |

## Non-negotiable behavioral gates

Before the backend is accepted, demonstrate against the actual implementation:

1. An uncertain append result is not treated as a confirmed failure; retry is safe only with duplicate-prevention evidence.
2. Concurrent or competing appends enforce the chosen expected-position rule atomically.
3. Reuse of an event ID or idempotency key with different content is detected under the adopted equality rule.
4. Ordered reads, pagination, restart/reopen, and recovery preserve the declared consistency contract.
5. Forks preserve an explicit source anchor and destination-side lineage.
6. Corruption, unsupported schema, incomplete reads, and unavailability remain distinguishable.

These are acceptance gates, not evidence that an adapter currently passes them. Integration test groups IT-1 through IT-6 remain specified but unexecuted until a real Android-hosted SQLite adapter exists.

## Backend evaluation rule

Device-local SQLite is the provisional target for the first Android-mobile implementation. Do not claim a working backend until the host integration, schema, transaction/durability settings, and recovery behavior are implemented and tested. If requirements later demand multi-device shared history, reopen the backend decision rather than assuming local SQLite meets it.

## Current conclusion

- **Chosen provisionally:** device-local SQLite; offline-capable local-first behavior.
- **Open:** Android host/runtime bridge, exact durability boundary, atomic append contract, trust boundary, pagination, backup, and migration.
- **Not adopted:** remote database or cross-device synchronization.
- **Not tested:** no real-backend integration tests.
- **Next action:** establish the Android host/integration path and implement a minimal SQLite-backed vertical slice, then run IT-1 through IT-6.
