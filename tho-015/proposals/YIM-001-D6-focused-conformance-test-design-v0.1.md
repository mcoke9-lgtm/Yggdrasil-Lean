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


## Regression execution result — 2026-11-11

- Commit under test: `a272a3d80e162cbae4747231c759f079723aff2e`.
- GitHub Actions run: [Core Memory System tests #43019845054](https://github.com/mcoke9-lgtm/Yggdrasil-Lean/actions/runs/43019845054).
- Job: `python-tests`; completed, conclusion: **failure**.
- Test command: `python -m unittest discover -s yim-001/tests -v`.
- Result: **76 tests run; 2 failures**. Both newly added tests failed exactly as predicted:
  - `test_malformed_history_precedes_duplicate_ambiguity`: actual `AMBIGUOUS`, expected `UNCERTAIN`.
  - `test_unreadable_history_precedes_duplicate_ambiguity`: actual `AMBIGUOUS`, expected `UNCERTAIN`.
- The other 74 tests passed.
- Package build and artifact upload were skipped because the test step failed.
- This verifies the direct API precedence defect in the current implementation. It does not establish persisted-ledger integration behavior.
- Source remains unchanged. No corrective implementation was made.


## Bounded correction and verification — 2026-11-11

- Corrective source commit: `d7283068522405e5d9143a476c22616a0a9b1792`.
- Changed only `yim-001/src/yim001/reconcile.py`:
  - malformed/unreadable history is now checked before duplicate-event ambiguity;
  - direct-conflict handling remains in its prior position;
  - comments explicitly preserve the unresolved direct-conflict versus malformed-history question rather than claiming it is decided.
- No specification, decision record, unrelated source, or test files were changed in this corrective step.
- Core Memory System CI: [run #43020607500](https://github.com/mcoke9-lgtm/Yggdrasil-Lean/actions/runs/43020607500) — **success**.
  - `python -m unittest discover -s yim-001/tests -v`: **76 tests passed**, including both new precedence regressions.
  - Portable package build/verification and artifact upload steps completed successfully.
- Lean build CI: [run #43020607542](https://github.com/mcoke9-lgtm/Yggdrasil-Lean/actions/runs/43020607542) — **success**.
- Verified scope: direct reconciliation API ordering and repository CI on this commit.
- Not verified: persisted-ledger → reconciliation integration, complete S2 write-gating conformance, recovery/repair, or the unresolved direct-conflict + malformed-history combination.


## Persisted integration and S2 gate audit — 2026-11-11

This audit follows corrective commit `d7283068522405e5d9143a476c22616a0a9b1792`. It is source/test inspection only; no source, test, or normative-specification changes were made in this audit.

### 1. Persisted-ledger → reconciliation seam

- `reconcile_creation_event()` accepts caller-supplied `CreationEvidence` rows and a caller-supplied `history_state` string.
- It does not accept a ledger path or `EventLedger`, call `read_json_strict()`, or call `EventLedger.from_snapshot()`.
- The inspected CLI has `event-show` and `event-verify`, but no reconciliation command or adapter.
- No production caller connecting persisted provenance to reconciliation was found in the inspected Python package.
- Therefore the direct API precedence correction is verified, but complete persisted-ledger validation before reconciliation remains **not integrated / not verified**. Probe 2's direct injection of `history_state="malformed"` is not evidence of this integration.

### 2. Current ordinary-write preconditions

| Operation | Observed source order | Audit result |
|---|---|---|
| Core Memory create | `load_store()` → read content/metadata → `create_record()` → `save_store()` | Complete existing Core Memory snapshot is loaded/validated before mutation. No provenance-ledger precondition is required under the adopted operation map. |
| Core Memory revise | `load_store()` → read content/metadata → `revise()` → `save_store()` | Same pre-mutation snapshot gate. |
| Core Memory annotate-conflict | `load_store()` → `annotate_conflict()` → `save_store()` | Same pre-mutation snapshot gate. |
| Provenance event-append | strict JSON read → `EventLedger.from_snapshot()` → in-memory append → atomic write | Complete ledger is validated before append/write. |
| Read-only show/verify commands | Load and validate complete corresponding snapshot/ledger before output | They do not repair or rewrite malformed history; current CLI reports an error rather than a structured reconciliation result. |

### 3. Remaining S2 conformance gaps and tests

**A. Rejected-write classification:** The CLI catches storage/provenance validation exceptions and prints an error with exit code 2. It does not emit a structured `UNCERTAIN` outcome for malformed/unreadable required history. The adopted specification says such a blocked ordinary write is reported as `UNCERTAIN`. This is a behavior gap to resolve, not merely a missing test.

**B. Memory mutator preservation tests:** Existing tests cover malformed *input content* and unknown records preserving bytes, but do not seed a valid store, corrupt the existing authoritative snapshot, then test each of create/revise/annotate-conflict for rejection and byte-for-byte unchanged store. Source order appears to gate these operations correctly; the behavior is not yet demonstrated by these targeted tests.

**C. Event append preservation tests:** Existing tests verify a tampered ledger is rejected by `event-verify`, but do not assert byte-for-byte unchanged ledger when `event-append` encounters malformed schema, invalid sequence, broken hash link, or unreadable/truncated JSON. Source order appears to gate append correctly; targeted preservation tests remain missing.

**D. Unrelated malformed event:** No integration test proves that a malformed event unrelated to the requested record prevents reconciliation or an ordinary append based on complete-history validation. The append path's `EventLedger.from_snapshot()` validates every event, but this needs a CLI no-mutation regression. Reconciliation needs an adapter before this can be tested end-to-end.

**E. Exception normalization:** Probe 2's list-valued `event_type` caused raw `TypeError` rather than `ProvenanceError`. Rejection occurred, but normalized schema failure remains a quality/conformance issue.

### 4. Corrected reconciliation status

The earlier audit row and execution-plan wording above that described malformed-history-before-duplicate as a current failing source behavior are historical and superseded by the corrective commit and successful run recorded above. Current direct API behavior for malformed/unreadable history versus duplicate ambiguity is **verified** by 76 passing tests. Direct conflict versus malformed-history precedence remains unresolved and unchanged.

### 5. Recommended next bounded implementation/test batch

1. Add memory-mutator tests for structurally malformed and unreadable existing snapshots; assert no authoritative bytes change for create, revise, and annotate-conflict.
2. Add event-append tests for malformed unrelated event/schema, boolean sequence, broken chain, and unreadable ledger; assert no ledger bytes change on every rejected append.
3. Decide whether to include structured `UNCERTAIN` in CLI errors as part of this batch or handle it in a separate narrowly scoped API/CLI change. The specification requires the classification, so tests should not silently settle for a generic exit-2 error.
4. Keep persisted-ledger reconciliation adapter as a separate integration task with its own API contract and tests; do not imply it exists today.
5. Do not resolve or encode direct-conflict versus malformed-history precedence without the user's decision.

**Audit conclusion:** Source ordering for the current ordinary mutators is consistent with pre-mutation validation at the relevant single-file boundary, but the missing preservation tests and generic CLI error classification leave S2 conformance incomplete. Persisted-ledger reconciliation integration is absent in the inspected package.
