"""Contract tests for mandatory post-connection reporting.

Run with: python -m unittest discover -s tests -v
Synthetic contract only; no provider connection is performed.
"""
import unittest

from src.adaptive_filesystem.connection_reporting import build_connection_report


class ConnectionReportingTests(unittest.TestCase):
    def make_report(self, outcome, **overrides):
        args = {
            "attempt_id": "attempt-001",
            "target": "synthetic-provider",
            "outcome": outcome,
            "started_at": "2026-11-07T00:00:00Z",
            "completed_at": "2026-11-07T00:00:01Z",
        }
        args.update(overrides)
        return build_connection_report(**args)

    def test_successful_connection_produces_report(self):
        report = self.make_report("SUCCESS")
        self.assertEqual(report["report_type"], "post_connection")
        self.assertEqual(report["outcome"], "SUCCESS")

    def test_partial_connection_produces_report(self):
        report = self.make_report("PARTIAL")
        self.assertEqual(report["outcome"], "PARTIAL")

    def test_blocked_connection_produces_report(self):
        report = self.make_report("BLOCKED")
        self.assertEqual(report["outcome"], "BLOCKED")

    def test_failed_connection_produces_report(self):
        report = self.make_report("FAILED", error="synthetic failure")
        self.assertEqual(report["outcome"], "FAILED")
        self.assertEqual(report["error"], "synthetic failure")

    def test_intended_changes_are_separate_from_applied_changes(self):
        report = self.make_report(
            "PARTIAL",
            intended_changes=[{"change_id": "C1", "status": "planned"}],
            applied_changes=[],
        )
        self.assertEqual(report["intended_changes"][0]["status"], "planned")
        self.assertEqual(report["applied_changes"], [])

    def test_missing_evidence_is_not_treated_as_verification(self):
        report = self.make_report("SUCCESS", evidence=[])
        self.assertEqual(report["evidence"], [])
        self.assertEqual(report["verification_status"], "UNVERIFIED")

    def test_invalid_outcome_is_rejected(self):
        with self.assertRaises(ValueError):
            self.make_report("UNKNOWN_OUTCOME")

    def test_required_attempt_identity_is_enforced(self):
        with self.assertRaises(ValueError):
            self.make_report("FAILED", attempt_id="")


if __name__ == "__main__":
    unittest.main()
