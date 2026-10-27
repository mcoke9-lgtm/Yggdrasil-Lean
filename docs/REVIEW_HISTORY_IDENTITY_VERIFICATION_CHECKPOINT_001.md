# Review History Identity Verification Checkpoint 001

**Status:** build-verified reference checks; identity policy remains unresolved.  
**Repository:** `mcoke9-lgtm/Yggdrasil-Lean`  
**Checkpoint date:** 2026-10-27

## Verified state

The top-level module `YggdrasilLean.lean` imports `YggdrasilLean.ReviewHistoryAppendHistoryConflictTests`.

The conflict-order checks are saved in:
- `YggdrasilLean/ReviewHistoryAppendHistoryConflictTests.lean`

The pre-existing order-sensitivity checks are saved in:
- `YggdrasilLean/ReviewHistoryAppendHistoryInteraction.lean`

GitHub Actions Lean build run #81 completed successfully for commit `b7591d0341fd012ace9ac89ec7bcd1aa5258ae55`:
https://github.com/mcoke9-lgtm/Yggdrasil-Lean/actions/runs/40431396077

The run's `leanprover/lean-action@v1` step completed with success. This verifies compilation in the configured repository build, not runtime behavior, durable storage, transactionality, or concurrent-writer safety.

## Current model behavior and unresolved policy

The current pure classifier treats a matching project-scoped idempotency key, branch, and payload as a retry and returns the stored event ID even if the attempted event ID differs.

If two supplied records both match the retry key/branch/payload but carry different event IDs, the reference model returns the first matching record's event ID. Reversing the records changes the result. The order-sensitivity theorem records this behavior; it does not endorse it.

The history fold gives a conflicting record precedence over a retry classification in either ordering. The four conflict-order theorems compile in the successful build.

## Next decision point

Before implementing a durable adapter, decide how to classify duplicate matching retry records that point to different event IDs. Candidate policies remain:
1. Treat the idempotency key as retry authority but regard multiple canonical records as history corruption/ambiguity.
2. Require the attempted event ID to match as well and classify mismatch as conflict.
3. Add a distinct ambiguous-identity outcome.

No option is adopted by this checkpoint. Prefer making ambiguity explicit rather than silently selecting a record based on history order. The policy must be reconciled with the existing decision record and adapter contract before implementation.

## Six-Part Integrity Check

1. **Count:** Six checkpoint dimensions are recorded below.
2. **Role mapping:** (a) build evidence; (b) imported test modules; (c) observed duplicate-retry behavior; (d) conflict precedence; (e) unresolved policy; (f) implementation boundary.
3. **Completeness:** No claim of durable storage or concurrency verification is made; identity ambiguity remains visible.
4. **Status distinctions:** Saved/retrieved source files and successful Lean build are verified; adapter implementation, integration tests, and policy adoption remain unverified/not adopted.
5. **Recheck:** Build run and imported module are identified by exact commit/run; theorem claims are limited to the pure reference model.
6. **Persistence and uncertainty:** This checkpoint is intended as a durable project record. The identity policy and backend behavior remain unresolved.
