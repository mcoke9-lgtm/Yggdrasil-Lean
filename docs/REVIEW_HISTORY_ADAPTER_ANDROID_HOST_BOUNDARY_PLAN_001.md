# Android Host Boundary and Vertical-Slice Plan 001

**Status:** proposed implementation plan; recommended host path recorded, not yet implemented or runtime-tested  
**Branch:** `review-history-identity-read-evidence-001`  
**Parent decisions:** Android-Mobile-First Backend Decision 001; Implementation Gate Decision Matrix 001  
**Scope:** identify the smallest honest path from the existing Lean reference model to a real Android-local SQLite adapter.

## 1. Repository inspection result

At inspected branch head `49dbfa2c9690990c4f7ac7b3a8ffe5f052b6e0b1`, the repository tree contains Lean source, documentation, Lake configuration, and a Lean GitHub Actions workflow. It contains no Android application module, Kotlin source, Gradle build files, Room/SQLite implementation, or native bridge. This is a bounded tree inspection, not a claim about code outside this repository.

Therefore, do not add a pretend SQLite implementation to the Lean reference model or describe the current project as an Android app.

## 2. Boundary to preserve

Use three separately testable layers:

1. **Lean reference model:** pure logical types and outcome classification. It must not claim to perform durable I/O.
2. **Android persistence adapter:** owns SQLite schema, transactions, uniqueness constraints, commit/reconciliation behavior, ordered reads, cursors, and migration.
3. **Host boundary:** a narrow, versioned request/response contract connecting the application to the adapter and, if retained in production, to the Lean model. The transport mechanism remains undecided until the intended Android host and Lean runtime are established.

The host must preserve all contract outcomes; it must not collapse conflict, missing history, corruption, unavailable evidence, or unknown commit status into a generic success/failure.

## 3. Smallest vertical slice

### Gate A — recommended default runtime boundary

**Recommended default:** let a native Android host (Kotlin/Java) own the SQLite adapter, while Lean remains the formal reference model used for specification and conformance checks. Do not package or bridge Lean into the Android app unless a concrete product requirement needs on-device Lean execution.

Why this is the least-assumptive first path: the inspected repository is Lean-only and contains no Android build/runtime integration; Android already provides SQLite access. This recommendation avoids inventing an unverified Lean-on-Android toolchain or bridge. It is a recommendation, not evidence that a host app exists.

Before creating a runnable Android module, confirm the intended app/package identity, minimum Android SDK, and build environment. Use Android's SQLite APIs directly for the smallest initial slice; Room can be considered later if its abstraction is useful. Room is optional and is not a different backend from SQLite.

If on-device Lean execution is later required, reopen this boundary and first prove the target Lean runtime/toolchain and supported foreign-function interface on Android.

### Gate B — schema and single-writer persistence proof

In the chosen Android host, create a minimal local database schema with explicit schema versioning and database-enforced uniqueness for the identities actually adopted by the contract. Do not finalize unique indexes until event-ID scope, idempotency scope, and logical payload-equality policy are resolved.

Implement one append operation in a transaction that:
- validates the expected branch position;
- writes the complete logical record and assigned position atomically;
- returns a receipt only when the commit boundary is known;
- distinguishes conflicts from uncertain commit outcomes.

Reopen the database after closing the adapter and verify the committed record is still readable. This is a smoke test, not sufficient durability evidence by itself.

### Gate C — readback and retry reconciliation

Implement lookup by event identity and ordered branch-history retrieval. Retry the same append after simulating a lost response; prove that reconciliation does not duplicate the logical event. Do not infer a commit from an exception or timeout.

### Gate D — full contract gate

Run all six specified integration-test groups IT-1 through IT-6 against the actual Android-hosted SQLite adapter. Include app/process restart and migration/recovery where specified. Record configuration, versions, results, skipped tests, and evidence. A Lean-only build cannot satisfy this gate.

## 4. Dependencies that block implementation claims

- Host/runtime boundary and whether Lean runs on-device.
- Event-ID and idempotency-key scopes.
- Logical payload equality/canonicalization.
- Expected-position and concurrent-writer semantics.
- Receipt and commit-unknown reconciliation semantics.
- Cursor/snapshot and ordered-read semantics.
- Schema migration, backup/export, and recovery policy.

These are not all blockers to a throwaway SQLite experiment, but they are blockers to claiming contract-conformant durable history.

## 5. Acceptance and reporting

- **Proposed:** this plan and the layer boundary; native Android host + SQLite adapter with Lean retained as the reference model is the recommended default.
- **Verified by repository inspection:** no Android/Kotlin/Gradle/SQLite implementation is present in the inspected tree.
- **Not implemented:** Android host, bridge, SQLite adapter, schema, and real-backend tests.
- **Not verified:** transaction/durability guarantees and IT-1 through IT-6.
- **Next justified action:** confirm app/package identity, minimum SDK, and build environment; then create the smallest native Android host slice using SQLite, while retaining Lean as the reference model. Do not merge the pull request or claim implementation success on the basis of this plan alone.

## Six-Part Integrity Check

1. **Count:** six checks.
2. **Roles:** (1) scope, (2) boundary, (3) completeness, (4) status, (5) correctness, (6) persistence/uncertainty.
3. **Completeness:** separates model, adapter, and host; identifies runtime and contract dependencies; no Android module is presumed to exist.
4. **Status:** plan proposed; repository absence verified; implementation and integration tests not done.
5. **Recheck:** findings are limited to the inspected branch tree at the stated SHA; they do not establish what exists outside this repository.
6. **Persistence:** this document is intended to be committed to the named branch; commit and CI results must be checked separately.
