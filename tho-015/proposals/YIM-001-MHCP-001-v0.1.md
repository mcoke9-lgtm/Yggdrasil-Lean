# YIM-001 — Malformed-History Precedence Corrective Proposal

**Artifact:** YIM-001-MHCP-001  
**Version:** 0.1  
**Status:** Proposed — not adopted, not implemented  
**Evidence basis:** THO-015 Probe 2 and source inspection at branch `tho-015/probe1-ci-bootstrap`  
**Scope:** Probe 2 only. The uncertain-commit/idempotency issue from Probe 1 is explicitly separate.

## 1. Problem statement

The adopted YIM-001 decision requires complete-history validation before record-specific reconciliation. Any malformed event anywhere in the history must cause `UNCERTAIN`, including when the malformed event is unrelated to the requested record and when otherwise-valid duplicate matching evidence would produce `AMBIGUOUS`. The user also adopted `UNCERTAIN` precedence over `AMBIGUOUS`.

THO-015 Probe 2 passed `history_state="malformed"` with two matching evidence rows to `reconcile_creation_event`. The observed result was `AMBIGUOUS`; expected was `UNCERTAIN`. This is a runtime-reproduced mismatch at the tested function boundary.

## 2. Source-level finding

Inspected file: `yim-001/src/yim001/reconcile.py`  
Inspected branch: `tho-015/probe1-ci-bootstrap`  
Inspected blob: `b6a6544acd377f5c8c0e9281eb00d511f7637e53`

The function currently:
1. records malformed/unreadable history in `findings`;
2. scans the supplied evidence rows and classifies matching rows;
3. resolves outcomes using this precedence comment and branch order:

```python
# Frozen precedence: CONFLICT > AMBIGUOUS > UNCERTAIN > INCOMPLETE
# > CONSISTENT > NOT_FOUND.
if direct_conflicts:
    outcome = Outcome.CONFLICT
elif len(event_rows) > 1:
    outcome = Outcome.AMBIGUOUS
elif history_state in {"malformed", "unreadable"}:
    outcome = Outcome.UNCERTAIN
```

Thus, the implementation explicitly places `AMBIGUOUS` ahead of malformed-history `UNCERTAIN`. It also places direct evidence conflicts ahead of malformed-history uncertainty. The adopted requirement that complete history be validated before record-specific outcomes appears broader than merely swapping two adjacent branches.

Inspected file: `yim-001/src/yim001/provenance.py`  
Inspected blob: `cb97b099c85fbad077180e774257d015625a16eb`

`EventLedger.from_snapshot` validates the complete supplied snapshot and rejects malformed event structures, types, sequence, hash links, and digests. Probe 2 observed rejection in all 11 tested malformed-snapshot cases. A list-valued `event_type` raised raw `TypeError` rather than `ProvenanceError`, although it was rejected.

**Important boundary:** the reconciliation function receives already-constructed `CreationEvidence` rows plus a caller-supplied `history_state` string. It does not itself receive or validate the complete `EventLedger`. Therefore the function-level correction alone cannot prove that every production caller validates the entire persisted history before creating the evidence envelope. The complete caller/integration path remains to be located and tested.

## 3. Proposed behavioral contract

Subject to confirmation against the exact adopted decision record:

1. The complete provenance history is loaded and validated before any record-specific outcome is calculated.
2. If the complete history is malformed or unreadable, reconciliation returns `UNCERTAIN` without allowing duplicate evidence or record-specific conflicts to override that history-integrity result.
3. If history is valid, existing record-specific precedence remains unchanged unless another adopted decision says otherwise.
4. If history is missing, preserve the currently specified `INCOMPLETE` behavior.
5. A malformed event anywhere in the ledger is material, whether or not it refers to the requested record.
6. The read-only reconciliation path must not mutate the ledger or memory store.
7. Write-capable operations must reject malformed history before performing a mutation, as required by the adopted A+B decision.

The phrase "without allowing ... record-specific conflicts to override" is a direct interpretation of "validate complete history before calculating record-specific outcomes" and "any malformed event anywhere yields UNCERTAIN." If the original decision record intended only UNCERTAIN-over-AMBIGUOUS while preserving CONFLICT priority, that narrower precedence must be confirmed before implementation. Do not infer it from current source comments.

## 4. Minimal corrective design (proposal only)

### A. Reconciliation function

At the earliest safe point after validating the `history_state` argument, add an explicit fail-closed return for `malformed` and `unreadable` history, before evidence rows are iterated or classified:

```python
if history_state in {"malformed", "unreadable"}:
    return ReconciliationResult(
        Outcome.UNCERTAIN,
        (f"history source is {history_state}",),
        (),
    )
```

This is illustrative proposed behavior, not an applied patch. It makes the complete-history status dominant and prevents malformed history from being reported as AMBIGUOUS or CONFLICT based on partial record-specific evidence. Preserve missing-history behavior as INCOMPLETE. Keep valid-history record-specific behavior intact.

### B. Full-history validation at the boundary

Identify the caller(s) that load provenance snapshots and construct `CreationEvidence`. Require them to:
- load the complete ledger using the validated snapshot loader;
- derive the history status from the load/validation result rather than accepting a caller assertion;
- only construct record-specific evidence after the full history has passed validation;
- pass `malformed` or `unreadable` status into reconciliation on failure, without discarding the failure state;
- never treat a partial set of valid matching rows as a valid complete history.

The current probe harness directly supplies `history_state="malformed"`; this tests outcome precedence but does not test this integration boundary. The call-site audit and integration tests are mandatory before claiming the adopted full-history rule is implemented.

### C. Exception normalization

Consider normalizing malformed event field types so malformed input consistently raises `ProvenanceError` rather than leaking raw `TypeError`. Keep this as a separate robustness subtask unless the normative schema already requires a specific exception class. Rejection is already observed; exception-class consistency was not established as an adopted requirement.

## 5. Required regression tests before acceptance

1. **Malformed + duplicate:** malformed history plus two otherwise-identical matching rows returns `UNCERTAIN`.
2. **Unrelated malformed event:** a malformed event for another record anywhere in the ledger makes the target reconciliation `UNCERTAIN`.
3. **Malformed + direct conflict:** malformed history plus otherwise-conflicting target evidence returns `UNCERTAIN`, if the broader complete-history-first interpretation in §3 is confirmed.
4. **Malformed + no rows:** returns `UNCERTAIN`.
5. **Valid + duplicate:** still returns `AMBIGUOUS`.
6. **Valid + direct conflict:** preserves the adopted/current valid-history conflict behavior.
7. **Missing history:** preserves `INCOMPLETE`.
8. **Malformed write gate:** every write-capable entry point rejects malformed history before any mutation; verify persisted bytes/state remain unchanged.
9. **Read-only reconciliation:** calling the reconciliation path does not mutate memory, ledger, or files.
10. **End-to-end loader path:** corrupt an unrelated event in a serialized ledger, invoke the actual public reconciliation path, and verify `UNCERTAIN` is returned.
11. **Exception robustness:** wrong-typed event fields are rejected through the documented validation boundary.

Tests 1–2 are directly required by the recovered adopted decision. Tests 3 and 6 are contingent on confirming the full-history-first interpretation and the retained valid-history conflict precedence. Tests 8–10 are necessary to verify the broader adopted A+B and read-only-path decisions.

## 6. Change sequence and acceptance gates

1. Recover the exact adopted decision wording and its source/revision linkage, especially whether malformed-history UNCERTAIN supersedes direct CONFLICT as well as AMBIGUOUS.
2. Map all provenance-load, reconciliation, and write-capable call sites. Do not assume the standalone function is the only path.
3. Finalize the expected precedence table from the recovered decision, not from existing implementation comments.
4. Implement the smallest source change and boundary validation.
5. Add regression tests listed above.
6. Run targeted tests, full YIM-001 tests, and the isolated THO-015 workflow.
7. Record source commit, test run IDs, runtime, and any remaining gaps. Only then propose adoption/release.

## 7. Explicitly excluded

- Probe 1 uncertain-commit retry and idempotency design. This remains a separate contract/design decision.
- Identifier policy changes (L2 remains as adopted).
- Symlink/path safety work outside Probe 3's N/A finding.
- Changes to MRP-001, AUD-001, or other normative artifacts except where separately authorized.
- Any production branch merge or release action.

## 8. Current disposition

**Finding:** Probe 2 is a confirmed implementation mismatch at the tested reconciliation function boundary. The source-level branch order explains the observed result.

**Proposal:** Fail closed to `UNCERTAIN` before record-specific classification when complete history is malformed/unreadable, subject to exact-decision wording recovery and caller-path audit.

**Not done:** No source code, tests, specifications, adopted decisions, or production branches have been changed by this proposal.
