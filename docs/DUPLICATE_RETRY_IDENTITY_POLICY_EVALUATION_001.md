# Duplicate Retry Identity Policy Evaluation 001

**Status:** evaluation and recommendation; not adopted.  
**Scope:** two or more stored records match one append intent's project, branch, idempotency key, and payload but have different event IDs.

## Verified baseline

The pure history classifier currently folds record-level decisions. A matching retry returns `alreadyCommitted eventId`; the fold retains the first such result. The theorem `matchingRetryRecords_areOrderSensitive` establishes that reversing two matching records with distinct event IDs reverses the returned event ID.

The current model also makes any detected identity conflict dominate a retry result, independent of list order. The conflict-order checks and top-level imports compile in GitHub Actions Lean build run #81:
https://github.com/mcoke9-lgtm/Yggdrasil-Lean/actions/runs/40431396077

The durable checkpoint document is saved and its documentation-only commit passed GitHub Actions Lean build run #82:
https://github.com/mcoke9-lgtm/Yggdrasil-Lean/actions/runs/40431604722

These builds verify compilation, not a durable backend or atomic uniqueness enforcement.

## Distinction that matters

Two different anomalies must not be conflated:

1. **Attempted event ID differs, but exactly one stored canonical record matches the key and logical payload.** This is the existing A/B/C policy question in `REVIEW_HISTORY_IDENTITY_EDGE_CASES_001.md`.
2. **More than one stored record matches the same retry identity but the records have different event IDs.** This means the supplied history does not identify one canonical retry target. Returning the first record is order-dependent and hides the inconsistency.

This document evaluates only case 2.

## Candidate policies

| Option | Behavior | Benefit | Cost / risk |
|---|---|---|---|
| D1 — First match wins | Keep current behavior | Minimal code change | Non-deterministic with respect to record ordering; conceals duplicate identity |
| D2 — Generic conflict | Return conflict when distinct matching event IDs are detected | Stops silent canonical selection; reuses existing result vocabulary | May blur caller intent conflict with inconsistent stored history |
| D3 — Explicit ambiguous/corrupt history | Return a distinct ambiguity/corruption result when duplicate retry identities point to different events | Preserves the distinction between a caller conflict and an internally inconsistent history | Requires extending pure decision types and aligning adapter/verification outcomes |
| D4 — Trust key, choose canonical by stable rule | Choose one record using a documented total ordering | Order-independent output | Still invents a canonical winner where uniqueness has failed; can hide corruption |

## Recommendation for evaluation

Do not retain D1 as accepted behavior. Prefer D3 if the contract can support an explicit result without disproportionate first-milestone expansion. If the adapter vocabulary is intentionally kept minimal, D2 is safer than first-match selection, but the contract must distinguish a conflict detected from the caller's intent from inconsistent duplicate records in diagnostics or verification evidence.

Do not choose D4 merely to force deterministic output: determinism alone does not establish which stored event is canonical.

This recommendation does not adopt a policy. Adoption requires reconciliation with:
- `docs/DURABLE_HISTORY_DECISION_RECORD_001.md`
- `docs/REVIEW_HISTORY_ADAPTER_CONTRACT_001.md`
- `docs/REVIEW_HISTORY_REFERENCE_MODEL_CONFORMANCE_001.md`
- `YggdrasilLean/ReviewHistoryAdapterTypes.lean`

## Required reference-model checks before adapter implementation

1. Two matching retry records with different event IDs produce an explicit non-retry ambiguity/corruption result in either list order, if D3 is selected.
2. A retry match plus a key/payload conflict remains conflict-dominant in either order.
3. A retry match plus same-event-ID/different-key conflict remains conflict-dominant in either order.
4. Repeated identical retry records with the same event ID are treated according to an explicit duplicate-record policy; do not infer uniqueness from identical IDs alone.
5. Incomplete or stale history never establishes global uniqueness; backend uniqueness must be enforced atomically.
6. Tests remain pure-model checks until storage-backed integration tests demonstrate retry safety, concurrency behavior, and recovery.

## Six-Part Integrity Check

1. **Count:** Exactly six implementation-gate checks are listed above.
2. **Role mapping:** (1) duplicate canonical events; (2) key/payload conflict; (3) event-ID conflict; (4) same-ID duplicates; (5) incomplete history; (6) backend evidence boundary.
3. **Completeness:** Both identity-conflict ordering cases and duplicate-record cases are represented; caller-ID mismatch remains separate.
4. **Statuses:** Existing order-sensitivity behavior and CI runs are verified; D3 is recommended for evaluation only; no policy is adopted; durable adapter and integration tests are not implemented.
5. **Recheck:** Claims are limited to the pure model and cited successful build runs.
6. **Persistence / uncertainty:** This evaluation is a durable record once committed; the final result vocabulary and policy remain open.

## Follow-up reference-model experiment (2026-10-27)

The pure reference model now has an `ambiguousHistory` constructor in `AppendIdentityDecision`. The multi-record combiner returns it when two retry matches identify different event IDs, independent of list order. Existing conflicts still dominate ambiguity and retry results. A matching retry record repeated with the same event ID remains an `alreadyCommitted` result.

The interaction module replaces the prior order-sensitive theorem with:
- `matchingRetryRecords_areAmbiguous`: distinct event IDs produce `ambiguousHistory` in both list orders.
- `matchingRetryRecords_sameEventId_isRetry`: same event ID repeated remains a retry.

GitHub Actions Lean build run #87 passed for commit `e4c94d09047f20c05a53d7f7fea878bb3a1d2fbc`:
https://github.com/mcoke9-lgtm/Yggdrasil-Lean/actions/runs/40431998467

**Status boundary:** the pure reference-model behavior is implemented and build-verified. This does not adopt the identity policy across the project or update the durable adapter contract/result vocabulary. The adapter's result mapping, schema evolution, duplicate-history verification, and storage-backed integration tests remain open. The model only examines the supplied history list and cannot prove global uniqueness or atomic backend enforcement.
