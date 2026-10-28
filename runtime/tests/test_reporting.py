import json
import unittest

from adaptive_filesystem_runtime import (
    Candidate, Record, WorkRequest, audit_snapshot, evaluate_work, resolve_history,
)
from adaptive_filesystem_runtime.reporting import (
    resolution_report, snapshot_audit_report, work_decision_report,
)


class ReportingTests(unittest.TestCase):
    def test_resolution_report_is_json_serializable_and_versioned(self):
        result = resolve_history([Record("a", "main", 1)], "a")
        report = resolution_report(result)
        self.assertEqual(report["schema_version"], "af.resolution.v1")
        self.assertEqual(report["record_id"], "a")
        self.assertEqual(json.loads(json.dumps(report))["parent_reference_state"], "blank_unclassified")

    def test_audit_report_keeps_status_and_coverage_separate(self):
        report = snapshot_audit_report(audit_snapshot([Record("a", "main", 1)]))
        self.assertEqual(report["schema_version"], "af.snapshot-audit.v1")
        self.assertEqual(report["status"], "passed")
        self.assertEqual(report["coverage_state"], "unknown")
        self.assertGreater(report["unclassified_count"], 0)

    def test_work_report_separates_recommendation_from_execution_and_persistence(self):
        request = WorkRequest(
            request_id="r1", active_branch="branch-x", checkpoint_id="AF-PILOT-006",
            current_goal="recover", blocked_operation="live read",
            observed_failure="credentials unavailable", blocked=True,
            candidates=(Candidate(
                candidate_id="local-audit", description="Audit cached snapshot",
                relevant=True, feasible=True, authorized=True, duplicate=False,
                integrity_ok=True, preserves_continuity=True, removes_dependency=True,
                concrete_progress=True,
            ),),
        )
        report = work_decision_report(evaluate_work(request))
        self.assertEqual(report["schema_version"], "af.work-decision.v1")
        self.assertEqual(report["outcome"], "SELECT_ALTERNATIVE")
        self.assertEqual(report["execution_status"], "not_attempted")
        self.assertEqual(report["persistence_status"], "not_persisted")
        self.assertEqual(report["active_branch"], "branch-x")
        self.assertEqual(json.loads(json.dumps(report))["checkpoint_id"], "AF-PILOT-006")

    def test_report_helpers_reject_wrong_dataclass_schema(self):
        audit = audit_snapshot([Record("a", "main", 1)])
        with self.assertRaises(TypeError):
            resolution_report(audit)
        with self.assertRaises(TypeError):
            work_decision_report(audit)

    def test_report_helper_rejects_non_dataclass_values(self):
        with self.assertRaises(TypeError):
            resolution_report({"record_id": "a"})


if __name__ == "__main__":
    unittest.main()
