# YIM-001 Decision 6 — Focused Conformance Test Design

**Artifact:** YIM-001-D6-CTD-001  
**Version:** 0.1  
**Status:** Test design and source-level conformance audit; no tests executed in this pass  
**Date:** 2026-11-11  
**Branch:** `tho-015/probe1-ci-bootstrap`  
**Authority:** `yim-001/WRITE-GATING-SPEC.md`

## 1. Scope

This pass maps the adopted write-gating specification to the existing test suite and source call paths. It does not modify source, tests, workflows, or the normative specification. Existing probe evidence is cited as historical evidence, not represented as a new execution.

## 2. Conformance audit

| Requirement / operation | Current source path | Existing evidence | Assessment |
|---|---|---|---|
| Complete memory snapshot validation before create | CLI loads via `load_store` before `create_record` and `save_store` | CLI tests cover invalid input and unknown records preserving store; not a structurally malformed existing store | Partial: source path validates snapshot; add targeted malformed-existing-store tests |
| Complete memory snapshot validation before revise | CLI loads via `load_store` before mutation | Same | Partial: add malformed-existing-store test proving no mutation |
| Complete memory snapshot validation before annotate-conflict | CLI loads via `load_store` before mutation | Directional annotation persistence test only | Partial: add malformed-existing-store test proving no mutation |
| Complete provenance history before event-append | CLI constructs `EventLedger.from_snapshot(read_json_strict(...))` before append/write | Tampered-ledger test rejects digest mismatch; Probe 2 rejected 11 malformed snapshots | Tested for representative cases; expand CLI preservation assertions for malformed schema/unreadable ledger |
| Strict integer schema validation | Event loader checks `type(sequence) is int`; format version also strict | Provenance boolean-sequence test; Probe 2 boolean sequence | Tested for sequence; add format-version boolean and any other integer fields |
| Malformed history before duplicate ambiguity | `reconcile_creation_event` currently checks duplicate count before malformed history | Probe 2 actual AMBIGUOUS, expected UNCERTAIN | **Known fail** |
| Full persisted history integrated into reconciliation | No persisted-ledger caller found; reconciliation accepts caller-supplied evidence and `history_state` | Probe 2 passes malformed state directly | **Not integrated / unverified** |
| Recovery/repair isolation | No recovery command/path identified | None | Not implemented; no bypass should be added |
| Direct conflict vs malformed-history precedence | Current source returns CONFLICT first | Existing conflict tests; no adopted resolution | Open decision; do not encode assumed expectation |

## 3. Proposed targeted tests

### A. Reconciliation precedence — immediate failing regression

Add to `yim-001/tests/test_reconcile.py`:

1. `test_malformed_history_precedes_duplicate_ambiguity`: two otherwise valid matching evidence rows and `history_state="malformed"` must return `UNCERTAIN`.
2. `test_unreadable_history_precedes_duplicate_ambiguity`: same with `history_state="unreadable"`, expected `UNCERTAIN`.
3. `test_malformed_unrelated_history_precedes_duplicate_ambiguity`: once a persisted-ledger adapter exists, include a malformed event unrelated to the requested record and duplicate matching evidence; expected `UNCERTAIN`.
4. Preserve the direct-conflict combination as a skipped/pending test until the user resolves `CONFLICT` versus `UNCERTAIN`.

**Current expected result:** Test 1 fails against the inspected source (actual `AMBIGUOUS`). Test 2 is expected to fail for the same branch ordering. These are predicted from source and the earlier Probe 2 reproduction, not newly executed test results.

### B. Memory mutator precondition tests

For each of `create`, `revise`, and `annotate-conflict`:

1. Seed a valid store, then corrupt the existing snapshot structurally (not merely malformed input content).
2. Capture exact bytes before invocation.
3. Invoke the mutator.
4. Assert nonzero exit, a clear validation error, and byte-for-byte unchanged store.
5. Include strict JSON parse failures (duplicate key, NaN, truncated JSON) and structurally invalid snapshot cases.
6. Assert the command does not create a replacement store or temporary residue that becomes authoritative.

The source currently loads the complete snapshot before mutation, so these should pass if the underlying validators reject each fixture. No separate provenance-ledger fixture belongs in these tests under the adopted operation map: the ledger is not currently a required precondition for these memory commands.

### C. Event append gate tests

Extend `test_cli.py` to cover:

1. malformed event schema in an unrelated event;
2. wrong-typed sequence including boolean;
3. broken previous-digest link;
4. unreadable/truncated ledger;
5. for every rejected append, exact ledger bytes remain unchanged.

The current event CLI loads and validates the ledger before append, but preservation should be asserted directly for each rejection case.

### D. Read-only diagnosis

For malformed Core Memory and provenance files:

1. `verify`, `show`, `event-verify`, and `event-show` must not mutate the source file;
2. each should fail/report the malformed or unreadable condition rather than attempt repair;
3. reconciliation should preserve findings and remain read-only.

### E. Exception normalization

Probe 2 observed a raw `TypeError` for a list-valued `event_type` during membership testing against `EVENT_TYPES`. The snapshot was rejected, but the public loader should ideally normalize malformed field types to `ProvenanceError`. Add a test asserting the documented exception class after hardening; do not misstate the existing behavior as passing.

## 4. Execution plan after authorization

1. Add the two direct API precedence regression tests first and demonstrate the known failure.
2. Resolve the direct `CONFLICT` versus malformed-history `UNCERTAIN` question before adding an assertion for that combination.
3. Add memory snapshot no-mutation tests and event-ledger no-mutation tests.
4. Add or identify an integration seam that loads the complete persisted ledger before reconciliation; do not pretend the current API already does so.
5. Fix source only under a separately authorized implementation scope; rerun the full suite and focused tests.
6. Preserve before/after test results, commit IDs, and workflow links.

## 5. Findings and status

- Source audit: completed for the inspected CLI, storage, provenance, reconciliation modules, and named test files.
- Existing known failing behavior: malformed history plus duplicate evidence returns `AMBIGUOUS`, contrary to adopted Decision 6 / Interaction 3.
- Existing evidence: THO-015 Probe 2 reproduced the discrepancy; 11/11 malformed provenance snapshots were rejected, with one raw `TypeError` rather than normalized `ProvenanceError`.
- Persisted-history/reconciliation integration: not found and remains unverified.
- Memory mutator source order: loads/validates complete memory snapshot before in-memory mutation and save; targeted malformed-existing-snapshot preservation tests are missing.
- Tests: two direct API regression tests have now been added to `yim-001/tests/test_reconcile.py`: malformed history before duplicate ambiguity, and unreadable history before duplicate ambiguity. Commit `a272a3d80e162cbae4747231c759f079723aff2e`.
- Execution: no associated workflow run was returned by the available commit-run lookup; the test results are therefore **not yet verified**. The expected failure is predicted from the inspected branch ordering and prior Probe 2 reproduction, not claimed as a new execution.
- Source: unchanged in this pass.
- Normative specification: unchanged in this pass.
- No new CI run was started.
