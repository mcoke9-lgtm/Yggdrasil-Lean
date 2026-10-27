"""End-to-end acceptance checks across resolver, audit, evaluator, and reports."""
import json
import unittest

from adaptive_filesystem_runtime import (
    Candidate, Record, ResolutionError, WorkRequest, audit_snapshot,
    evaluate_work, resolution_report, snapshot_audit_report, work_decision_report,
    resolve_history,
)


def fixture():
    return [
        Record("AF-PILOT-001", "main", 1),
        Record("AF-PILOT-002", "main", 2, parent_record_id="AF-PILOT-001"),
        Record("AF-PILOT-003", "main", 3, parent_record_id="AF-PILOT-002"),
        Record("AF-PILOT-004", "fork", 1, parent_record_id="AF-PILOT-003",
               source_branch_id="main", source_anchor_id="AF-PILOT-001"),
        Record("AF-PILOT-005", "broken-fork", 1, parent_record_id="AF-PILOT-004",
               source_branch_id="main", source_anchor_id="AF-ANCHOR-DOES-NOT-EXIST"),
        Record("AF-PILOT-006", "main", 4, parent_record_id="AF-PILOT-005"),
        Record("AF-PILOT-007", "main", 5),  # Live read: parent blank, not inferred.
        Record("AF-PILOT-008", "main", 6, parent_record_id="AF-PILOT-007"),
    ]


def work_request(**updates):
    values = dict(
        request_id="AF-AW-ACCEPT-001",
        active_branch="chatgpt-first-integration",
        checkpoint_id="AF-PILOT-006",
        current_goal="recover and verify branch continuity",
        blocked_operation="fresh live Airtable read",
        observed_failure="credentials unavailable",
        blocked=True,
        candidates=(
            Candidate(
                candidate_id="local-audit",
                description="Audit the available snapshot locally",
                relevant=True, feasible=True, authorized=True, duplicate=False,
                integrity_ok=True, preserves_continuity=True,
                removes_dependency=True, concrete_progress=True,
            ),
            Candidate(
                candidate_id="guess-missing-link",
                description="Infer missing parent from sequence",
                relevant=True, feasible=True, authorized=True, duplicate=False,
                integrity_ok=False, preserves_continuity=True,
                removes_dependency=True, concrete_progress=True,
            ),
        ),
    )
    values.update(updates)
    return WorkRequest(**values)


class AcceptanceWorkflowTests(unittest.TestCase):
    def test_branch_history_and_fork_origin_remain_distinct(self):
        records = fixture()
        fork = resolve_history(records, "AF-PILOT-004")
        self.assertEqual(fork.parent_record_id, "AF-PILOT-003")
        self.assertEqual(fork.source_anchor_id, "AF-PILOT-001")
        self.assertIsNone(fork.branch_predecessor_id)
        main_tip = resolve_history(records, "AF-PILOT-006")
        self.assertEqual(main_tip.parent_record_id, "AF-PILOT-005")
        self.assertEqual(main_tip.branch_predecessor_id, "AF-PILOT-003")

    def test_broken_fork_anchor_is_reported_and_not_repaired(self):
        records = fixture()
        audit = audit_snapshot(records, coverage="partial")
        self.assertEqual(audit.status, "failed")
        self.assertEqual(audit.coverage_state, "declared_partial")
        self.assertTrue(any(f.code == "MISSING_SOURCE_ANCHOR"
                            and f.record_id == "AF-PILOT-005" for f in audit.findings))
        with self.assertRaises(ResolutionError) as caught:
            resolve_history(records, "AF-PILOT-005")
        self.assertEqual(caught.exception.code, "MISSING_SOURCE_ANCHOR")

    def test_blank_parent_stays_unknown_while_branch_predecessor_is_known(self):
        result = resolve_history(fixture(), "AF-PILOT-007")
        self.assertIsNone(result.parent_record_id)
        self.assertEqual(result.parent_reference_state, "blank_unclassified")
        self.assertEqual(result.branch_predecessor_id, "AF-PILOT-006")

    def test_evaluator_selects_safe_alternative_and_rejects_guess(self):
        decision = evaluate_work(work_request())
        self.assertEqual(decision.outcome, "SELECT_ALTERNATIVE")
        self.assertEqual(decision.recommended_candidate_ids, ("local-audit",))
        rejected = {a.candidate_id: a for a in decision.candidate_assessments}
        self.assertFalse(rejected["guess-missing-link"].eligible)
        self.assertIn("integrity_unknown_or_false",
                      rejected["guess-missing-link"].rejected_reasons)
        self.assertEqual(decision.active_branch, "chatgpt-first-integration")
        self.assertEqual(decision.checkpoint_id, "AF-PILOT-006")
        self.assertEqual(decision.execution_status, "not_attempted")
        self.assertEqual(decision.persistence_status, "not_persisted")

    def test_authorization_failure_blocks_candidate_and_retry_is_bounded(self):
        unauthorized = Candidate(
            candidate_id="write-record", description="Write a pilot record",
            relevant=True, feasible=True, authorized=False, duplicate=False,
            integrity_ok=True, preserves_continuity=True, removes_dependency=True,
            concrete_progress=True,
        )
        blocked = evaluate_work(work_request(
            candidates=(unauthorized,), search_scope_exhausted=False))
        self.assertEqual(blocked.outcome, "UNRESOLVED")
        self.assertFalse(blocked.candidate_assessments[0].eligible)
        retry = evaluate_work(work_request(
            candidates=(), retry_requested=True, retry_material_change=True,
            prior_retry_count=1, retry_budget=1, search_scope_exhausted=False))
        self.assertEqual(retry.outcome, "UNRESOLVED")

    def test_reports_are_machine_readable_and_keep_uncertainty_explicit(self):
        records = fixture()
        audit_report = snapshot_audit_report(audit_snapshot(records, coverage="partial"))
        resolution = resolution_report(resolve_history(records, "AF-PILOT-007"))
        decision_report = work_decision_report(evaluate_work(work_request()))
        self.assertEqual(audit_report["schema_version"], "af.snapshot-audit.v1")
        self.assertEqual(audit_report["coverage_state"], "declared_partial")
        self.assertGreater(audit_report["error_count"], 0)
        self.assertEqual(resolution["parent_reference_state"], "blank_unclassified")
        self.assertEqual(resolution["branch_predecessor_id"], "AF-PILOT-006")
        self.assertEqual(decision_report["schema_version"], "af.work-decision.v1")
        self.assertEqual(decision_report["checkpoint_id"], "AF-PILOT-006")
        self.assertEqual(decision_report["persistence_status"], "not_persisted")
        json.dumps(audit_report)
        json.dumps(resolution)
        json.dumps(decision_report)


if __name__ == "__main__":
    unittest.main()
