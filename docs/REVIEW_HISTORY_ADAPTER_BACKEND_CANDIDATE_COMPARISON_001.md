# Review History Adapter Backend Candidate Comparison 001

**Status:** Android-mobile-first target selected provisionally; implementation not yet present.  
**Branch:** `review-history-identity-read-evidence-001`  
**Decision record:** [Android-Mobile-First Backend Decision 001](REVIEW_HISTORY_ADAPTER_ANDROID_MOBILE_BACKEND_DECISION_001.md).

## Decision boundary

The immediate target is an Android mobile workflow that can retain durable review history locally and operate offline. For this target, **device-local SQLite is the provisional backend choice**. This does not establish that the repository's Lean library has an Android host, SQLite binding, or working adapter. The choice is reversible if a later requirement establishes shared multi-device history.

## Candidate fit for Android mobile

| Candidate family | Fit for current target | Decision |
|---|---|---|
| In-memory state | Offline and simple but loses history across process restart | Reject for durable history |
| Local append-only files | Possible, but requires custom atomicity, recovery, indexing, and concurrent-write rules | Not preferred for first implementation |
| Device-local SQLite | Available on Android; transactions and constraints can support durable ordered records and atomic operations when correctly implemented/configured | **Provisionally selected** |
| Client/server database | Adds network, service availability, authentication, and operational dependencies; unnecessary for a single-device offline-first first target | Defer unless shared history becomes required |

If the Android host is Kotlin, Android SQLite APIs or Room may be considered as the integration layer. Room is an abstraction over SQLite, not a separate persistence-engine choice. If storage must be accessed directly from Lean, a compatible SQLite binding and Android build path must be demonstrated first.

## Implementation gates

1. Identify the Android host boundary and Lean integration mechanism.
2. Define schema, migrations, stable event identity, idempotency scope, and payload equality.
3. Enforce append plus expected-position checks transactionally.
4. Reconcile uncertain commit outcomes without duplicate events.
5. Define ordered history, pagination/cursor, and snapshot semantics.
6. Preserve exact original assessments, identity/provenance, and fork source anchors.
7. Distinguish corruption, unsupported schema, incomplete reads, and unavailability.
8. Test restart/reopen, recovery, backup/export, and migration behavior.
9. Execute integration tests IT-1 through IT-6 against the actual Android-hosted adapter.

Unknown behavior must remain unknown, not be scored as a pass. Lean's pure classifier does not prove SQLite query completeness or operational trust.

## Current decision

- **Chosen provisionally:** device-local SQLite for Android-mobile-first use.
- **Known requirement:** durable history preserves identity/provenance, exact snapshots and original assessments, atomic/idempotent writes, ordered retrieval after restart, corruption/failure reporting, and the distinction between original assessment and reassessment.
- **Unresolved:** Android host/runtime bridge, exact durability configuration, trusted read-completeness boundary, cursor/snapshot semantics, migration and backup behavior.
- **Not selected:** client/server database, cross-device synchronization.
- **Not implemented or tested:** real adapter and all backend integration gates.
- **Next justified action:** establish the Android integration boundary and build the smallest SQLite-backed vertical slice, then run IT-1 through IT-6.
