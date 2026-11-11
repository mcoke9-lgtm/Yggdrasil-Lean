# THO-015 — Probe 2 Malformed-History Evidence

**Probe:** THO-015-PROBE-2  
**Status:** FAIL — adopted reconciliation precedence not satisfied  
**Scope:** Disposable test harness only; no YIM-001 source or production data modified  
**Branch:** `tho-015/probe1-ci-bootstrap`  
**Execution commit:** `19435a7a3c64b3bae0f57d42cd1138496384bfd8`  
**GitHub Actions run:** https://github.com/mcoke9-lgtm/Yggdrasil-Lean/actions/runs/43015708450  
**Runtime:** CPython 3.13.18; Ubuntu 26.04.1 LTS; Linux x86_64; GitHub-hosted runner

## 1. Test objective

Check whether malformed or wrong-typed event data is rejected when a complete provenance snapshot is loaded, including a malformed event unrelated to the requested record. Separately check the adopted reconciliation outcome precedence: malformed history anywhere must produce `UNCERTAIN` before record-specific duplicate evidence can produce `AMBIGUOUS`.

## 2. Complete-snapshot validation results

The harness mutated the second event in a two-event snapshot. The second event referred to a different record from the first. All 11 malformed snapshots were rejected by `EventLedger.from_snapshot`; none were accepted.

| Malformation | Observed result |
|---|---|
| Boolean sequence | Rejected: ProvenanceError |
| String sequence | Rejected: ProvenanceError |
| List event_type | Rejected: TypeError (unhashable list) |
| Object actor_id | Rejected: ProvenanceError |
| Integer timestamp | Rejected: ProvenanceError |
| List record_id | Rejected: ProvenanceError |
| NaN in details | Rejected: ValidationError |
| List previous_digest | Rejected: ProvenanceError |
| Integer digest | Rejected: ProvenanceError |
| Missing required field | Rejected: ProvenanceError |
| Unknown extra field | Rejected: ProvenanceError |

**Finding:** The ledger loader rejects each malformed snapshot, including malformed data in the unrelated second event. One wrong-typed `event_type` raises raw `TypeError` rather than the module's `ProvenanceError`; rejection occurred, but exception normalization is a separate quality observation.

## 3. Reconciliation precedence result

Input:
- Two otherwise valid matching `CreationEvidence` rows with the same requested event identity.
- `history_state="malformed"`.

Adopted expected outcome: `UNCERTAIN`  
Observed outcome: `AMBIGUOUS`

**Finding:** FAIL. The inspected `reconcile_creation_event` implementation applies duplicate ambiguity before malformed-history uncertainty. This conflicts with the user's adopted decision that complete-history validation occurs first and that any malformed event anywhere makes the outcome `UNCERTAIN`, including when valid duplicate evidence exists.

This is a runtime reproduction of the previously identified source-level precedence discrepancy.

## 4. Overall result

`THO015_PROBE2_RESULT=FAIL`

- 11/11 malformed provenance snapshots rejected.
- 0 malformed snapshots accepted.
- 1 raw TypeError observed for a wrong-typed list event_type; still rejected.
- Reconciliation precedence: expected UNCERTAIN, actual AMBIGUOUS.
- No YIM-001 source changes.
- No specification changes.
- No fix or policy change adopted.

## 5. Evidence and limitations

The GitHub Actions job completed the Probe 1 step successfully and then executed Probe 2. Probe 2 returned exit code 1 because the observed reconciliation result did not match the adopted precedence requirement. The workflow therefore reports failure by design: the failing check is evidence, not a runner/setup failure.

The test passes `history_state="malformed"` directly to the reconciliation API; it does not establish that a higher-level caller currently validates the entire event ledger and supplies this state correctly. The ledger-loading check independently establishes that `EventLedger.from_snapshot` rejects the tested malformed snapshots. Integration between ledger loading and reconciliation remains unverified.

## 6. Change control

This report is an append-only evidence artifact. The harness and workflow are isolated under the THO-015 branch. No production branch, YIM-001 source, adopted decision, or normative specification was modified.
