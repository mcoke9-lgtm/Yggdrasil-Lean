# Android-Mobile-First Backend Decision 001

**Status:** provisional backend choice adopted for the current target; implementation not yet present.  
**Branch:** `review-history-identity-read-evidence-001`  
**Scope:** first usable Review History Adapter target, prioritizing Android mobile.

## Decision

Use a **device-local SQLite database** as the first backend target.

- Keep the first implementation local to the Android device and usable offline.
- Require durable records across app/process restarts, subject to the documented SQLite transaction and durability configuration.
- Use database transactions and database-enforced constraints for atomic append, expected-position checks, and idempotency; prove the actual contract with integration tests.
- Keep the persistence adapter separate from the Lean pure outcome/classification model.
- Treat Android integration and the language/runtime bridge as open implementation work. The current repository's Lean library does not itself provide an Android application, SQLite binding, or working adapter.
- Do not use an in-memory-only backend for durable history. Do not introduce a client/server database for the first mobile target unless a later requirement establishes cross-device/shared history.

## Why this is the current fit

SQLite is a standard on-device relational storage engine available on Android, supports offline local storage, and offers transactions suitable for implementing atomic history operations. These are reasons to choose it as the target, not proof that this repository's future adapter already meets the required guarantees.

If the first Android host is implemented in Kotlin, Android's SQLite APIs or Room may be considered as the host-side integration layer; Room is not itself a separate storage engine decision. If the adapter must run directly in Lean, a supported Android-compatible SQLite binding and build/integration path must first be demonstrated.

## Still unresolved

1. The Android host boundary and the Lean-to-Android integration mechanism.
2. Exact transaction/durability settings and behavior under process termination or device power loss.
3. Stable ordering, cursor and snapshot semantics.
4. Event-ID creation, payload equality, and idempotency-key constraints.
5. Backup/export, schema migration, and recovery behavior.
6. Trusted identity-lookup completeness and access-control boundary.

## Acceptance gate

Before calling the backend implemented or verified, run IT-1 through IT-6 against the actual Android-hosted SQLite adapter. Include app restart/reopen, uncertain commit reconciliation, concurrent or competing append attempts where supported by the host, schema migration, corruption/error handling, and ordered history recovery. Record skipped tests explicitly.

## Status summary

- **Chosen provisionally:** device-local SQLite for Android-mobile-first use.
- **Not implemented:** durable adapter and Android integration.
- **Not verified:** real-backend integration tests IT-1 through IT-6.
- **Not adopted:** cross-device synchronization or remote database.
- **Reversible:** revisit if requirements establish shared multi-device history or a different runtime boundary.
