# YIM-001-MHCP-001 — Decision Recovery and Call-Site Audit Addendum

**Artifact:** YIM-001-MHCP-001-A  
**Version:** 0.2 addendum  
**Status:** Evidence/recovery update — no decision, specification, or implementation change  
**Branch:** `tho-015/probe1-ci-bootstrap`  
**Parent proposal:** [YIM-001-MHCP-001 v0.1](./YIM-001-MHCP-001-v0.1.md)

## 1. Recovered adopted decision wording

Prior conversation recovery located the original YIM-001 Interaction 3 decision, Option A, selected on 2026-11-10 at 04:51:17 and recorded at 04:51:22.

The recovered resolution is:

> Validate the complete history first; any malformed event anywhere yields `UNCERTAIN`, even for another record; malformed history plus duplicates remains `UNCERTAIN`.

The user's selected option was explicitly: **`UNCERTAIN` takes precedence over `AMBIGUOUS`.**

The decision was decision-level only. At the time it was recorded, the specification, source, and tests were not changed. The later outline was a proposal, not an adopted revision.

### Precedence boundary — do not infer

The recovered decision explicitly settles:
- malformed history versus duplicate evidence / `AMBIGUOUS`: `UNCERTAIN` wins;
- malformed unrelated events: they still make the overall history `UNCERTAIN`;
- complete-history validation occurs before record-specific outcome calculation.

The recovered decision does **not** separately state whether malformed-history `UNCERTAIN` overrides a direct record-specific `CONFLICT`. Therefore:
- the current source's `CONFLICT > ... > UNCERTAIN` ordering is not evidence of user authorization;
- this addendum does not adopt either `UNCERTAIN > CONFLICT` or `CONFLICT > UNCERTAIN`;
- exact precedence for that pair remains an open decision question;
- implementation must not silently resolve that question by relying on current code comments.

This narrows the earlier proposal's claim: the minimum confirmed correction is `UNCERTAIN` before `AMBIGUOUS` when history is malformed, with complete-history validation before record-specific classification. The direct-conflict pair remains unresolved.

## 2. Repository call-site audit

**Inspected ref:** `tho-015/probe1-ci-bootstrap`  
**Repository:** `mcoke9-lgtm/Yggdrasil-Lean`

The complete Python module and test-file listing under `yim-001/src/yim001` and `yim-001/tests` was inspected. Relevant paths:

### A. Reconciliation API

- `yim-001/src/yim001/reconcile.py`
  - Defines `reconcile_creation_event()`.
  - Accepts an iterable of `CreationEvidence` and a caller-supplied `history_state`.
  - Does not load a provenance file, accept an `EventLedger`, or validate the complete event history itself.
- `yim-001/src/yim001/__init__.py`
  - Re-exports `reconcile_creation_event` as a public package API.
- Repository search of the inspected module/test tree found no production caller of `reconcile_creation_event()`. Its only references in the inspected Python tree are its definition/export and tests.

**Finding:** the current repository contains a standalone reconciliation function but no integrated provenance-loader-to-reconciliation call path. Thus Probe 2 proves the function's precedence mismatch, but the repository does not currently demonstrate end-to-end reconciliation against a persisted complete ledger.

### B. Provenance read/validation

- `yim-001/src/yim001/provenance.py`
  - `EventLedger.from_snapshot()` validates the complete supplied snapshot: envelope, event structure, sequence, event identity, event type, actor, timestamp, record/version identifiers, JSON details, hash-link continuity, and event digest.
- `yim-001/src/yim001/storage.py`
  - `read_json_strict()` parses strict JSON and rejects malformed JSON, duplicate object keys, non-finite constants, and unreadable files.
  - It does not itself construct an `EventLedger`.
- `yim-001/src/yim001/__main__.py`
  - For `event-append`, `event-verify`, and `event-show`, first calls `read_json_strict()` and then `EventLedger.from_snapshot()` on the complete ledger before proceeding.
  - `event-append` appends only after that full load/validation succeeds, then writes the updated ledger.
  - `event-show` and `event-verify` are read-only CLI paths with respect to the ledger.

**Finding:** complete-ledger validation-before-append is present for the separate provenance-ledger CLI path. This does not connect the ledger to `reconcile_creation_event()`, and it does not prove every memory-store write is gated by provenance-history validation.

### C. Memory-store write paths

In `__main__.py`, `create`, `revise`, and `annotate-conflict` call `load_store()`, mutate the MemoryStore, and save the memory snapshot. They do not load or validate the separate provenance ledger.

**Finding:** the current CLI memory-store mutation paths are not coupled to the provenance ledger. If the adopted A+B decision is intended to require malformed provenance history to block all memory-store writes, the present CLI path does not demonstrate that integration. Whether A+B applies to these exact operations should be checked against the full decision record/specification before treating this as a defect.

### D. Tests

- `yim-001/tests/test_reconcile.py` tests function-level outcome precedence, including:
  - `AMBIGUOUS` for duplicate matching evidence;
  - `UNCERTAIN` for malformed history with one good row;
  - `UNCERTAIN` over `INCOMPLETE` for unreadable history;
  - `CONFLICT` over `AMBIGUOUS` for valid-history evidence conflict.
- It does not test malformed history combined with duplicate matching rows (the THO-015 Probe 2 harness does), malformed unrelated ledger event through a public end-to-end API, or a write gate joining memory-store writes to provenance validation.
- `yim-001/tests/test_cli.py` tests malformed/tampered provenance ledger rejection for event CLI operations. It does not test memory-store writes with a malformed separate ledger.

## 3. Updated corrective scope

The proposal should be split into two independently verifiable layers:

1. **Function-level precedence:** establish the confirmed `UNCERTAIN`-over-`AMBIGUOUS` behavior for malformed history. Keep direct `CONFLICT` precedence unresolved pending the original decision record or a new user decision.
2. **Integration architecture:** decide and specify whether reconciliation is intended to consume the validated complete ledger directly or receive a trusted validated-history result from a dedicated boundary. Current code has no production integration point.
3. **Write gating:** recover the exact A+B decision scope before changing memory-store CLI writes. The existing event-append path already validates the ledger before appending to that ledger; memory-store writes currently operate independently.
4. **Read-only path:** the reconciliation function does not mutate supplied evidence in its current implementation, but no persisted-ledger reconciliation endpoint exists to verify as a distinct read-only open path.

## 4. Change control

No source, tests, normative specifications, adopted decisions, or production branches were changed. This addendum records recovered decision boundaries and the inspected call-site architecture only.
