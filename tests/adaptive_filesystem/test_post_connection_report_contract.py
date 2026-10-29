"""Separate synthetic regression tests for the AF peer-container/report contract.

These tests specify behavior; they do not prove production-runtime integration.
Run with: python -m unittest discover -s tests/adaptive_filesystem -v
"""
import unittest
from copy import deepcopy

REQUIRED_PEER_CONTAINERS = {
    "tera",
    "fira",
    "wata",
    "next_steps_intended",
}
ATTEMPT_OUTCOMES = {"SUCCESS", "PARTIAL", "BLOCKED", "FAILED"}
REPORT_STATUSES = {
    "GENERATED_VERIFIED",
    "GENERATED_UNVERIFIED",
    "FAILED",
    "NOT_GENERATED",
    "UNKNOWN",
}


def validate_peer_inventory(inventory):
    """Validate structural peers separately from fields inside each peer."""
    if not isinstance(inventory, dict):
        return {"status": "FAIL", "missing": sorted(REQUIRED_PEER_CONTAINERS)}
    missing = sorted(REQUIRED_PEER_CONTAINERS - set(inventory))
    malformed = sorted(
        name for name in REQUIRED_PEER_CONTAINERS
        if name in inventory and not isinstance(inventory[name], dict)
    )
    return {
        "status": "PASS" if not missing and not malformed else "FAIL",
        "missing": missing,
        "malformed": malformed,
    }


def generate_post_connection_report(attempt):
    """Synthetic report contract: every known attempt outcome yields a report."""
    if not isinstance(attempt, dict) or attempt.get("outcome") not in ATTEMPT_OUTCOMES:
        return {
            "report_status": "UNKNOWN",
            "reason": "attempt_or_outcome_unavailable",
        }
    if attempt.get("generation_error"):
        return {
            "report_status": "FAILED",
            "attempt_id": attempt.get("attempt_id"),
            "source": attempt.get("source", "UNKNOWN"),
            "destination": attempt.get("destination", "UNKNOWN"),
            "observed_outcome": attempt["outcome"],
            "evidence_refs": deepcopy(attempt.get("evidence_refs", [])),
            "discrepancies": deepcopy(attempt.get("discrepancies", [])),
            "unknowns": deepcopy(attempt.get("unknowns", [])),
            "generation_error": attempt["generation_error"],
        }
    return {
        "report_status": (
            "GENERATED_VERIFIED" if attempt.get("verification") == "PASS"
            else "GENERATED_UNVERIFIED"
        ),
        "attempt_id": attempt.get("attempt_id"),
        "source": attempt.get("source", "UNKNOWN"),
        "destination": attempt.get("destination", "UNKNOWN"),
        "observed_outcome": attempt["outcome"],
        "evidence_refs": deepcopy(attempt.get("evidence_refs", [])),
        "discrepancies": deepcopy(attempt.get("discrepancies", [])),
        "unknowns": deepcopy(attempt.get("unknowns", [])),
        "intended_changes": deepcopy(attempt.get("intended_changes", [])),
        "actual_changes": deepcopy(attempt.get("actual_changes", [])),
        "verification": attempt.get("verification", "UNKNOWN"),
        "next_steps_intended_ref": attempt.get("next_steps_intended_ref", "UNKNOWN"),
    }


def complete_attempt(attempt, report):
    """Completion is not allowed when a report was omitted or is untrustworthy."""
    if not isinstance(report, dict):
        return False
    return report.get("report_status") in {
        "GENERATED_VERIFIED", "GENERATED_UNVERIFIED", "FAILED"
    } and report.get("attempt_id") == attempt.get("attempt_id")


class PostConnectionReportContractTests(unittest.TestCase):
    def test_01_missing_peer_container_fails_even_if_existing_peers_are_valid(self):
        inventory = {
            "tera": {}, "fira": {}, "wata": {},
            # Deliberately omit next_steps_intended.
        }
        result = validate_peer_inventory(inventory)
        self.assertEqual(result["status"], "FAIL")
        self.assertEqual(result["missing"], ["next_steps_intended"])

    def test_02_next_steps_intended_is_a_peer_container_not_a_scalar_field(self):
        inventory = {
            "tera": {}, "fira": {}, "wata": {},
            "next_steps_intended": {
                "changes_intended": [],
                "implementation_plan": {"next_action": "inspect"},
                "post_connection_report_requirement": "mandatory",
            },
        }
        self.assertEqual(validate_peer_inventory(inventory)["status"], "PASS")
        self.assertIsInstance(inventory["next_steps_intended"], dict)
        self.assertIn("next_action", inventory["next_steps_intended"]["implementation_plan"])

    def test_03_every_known_attempt_outcome_produces_a_report(self):
        for outcome in sorted(ATTEMPT_OUTCOMES):
            with self.subTest(outcome=outcome):
                attempt = {"attempt_id": "A-1", "outcome": outcome}
                report = generate_post_connection_report(attempt)
                self.assertIn(report["report_status"], REPORT_STATUSES)
                self.assertEqual(report["attempt_id"], "A-1")
                self.assertEqual(report["observed_outcome"], outcome)

    def test_04_generated_but_unverified_is_not_verified(self):
        attempt = {"attempt_id": "A-2", "outcome": "SUCCESS", "verification": "PENDING"}
        report = generate_post_connection_report(attempt)
        self.assertEqual(report["report_status"], "GENERATED_UNVERIFIED")
        self.assertNotEqual(report["report_status"], "GENERATED_VERIFIED")

    def test_05_report_generation_failure_is_preserved(self):
        attempt = {
            "attempt_id": "A-3", "outcome": "BLOCKED",
            "generation_error": "serializer unavailable",
        }
        report = generate_post_connection_report(attempt)
        self.assertEqual(report["report_status"], "FAILED")
        self.assertEqual(report["observed_outcome"], "BLOCKED")

    def test_06_missing_attempt_evidence_stays_unknown_and_cannot_complete(self):
        attempt = {"attempt_id": "A-4", "outcome": "SUCCESS"}
        report = generate_post_connection_report(None)
        self.assertEqual(report["report_status"], "UNKNOWN")
        self.assertFalse(complete_attempt(attempt, report))

    def test_07_report_keeps_intended_changes_separate_from_actual_changes(self):
        attempt = {
            "attempt_id": "A-5", "outcome": "PARTIAL",
            "intended_changes": [{"id": "C1", "status": "planned"}],
            "actual_changes": [],
            "next_steps_intended_ref": "NSI-5",
        }
        report = generate_post_connection_report(attempt)
        self.assertEqual(report["intended_changes"][0]["status"], "planned")
        self.assertEqual(report["actual_changes"], [])
        self.assertEqual(report["next_steps_intended_ref"], "NSI-5")

    def test_08_report_is_linked_to_attempt_and_intended_next_steps(self):
        attempt = {
            "attempt_id": "A-6", "outcome": "SUCCESS",
            "next_steps_intended_ref": "NSI-6",
            "verification": "PASS",
        }
        report = generate_post_connection_report(attempt)
        self.assertEqual(report["attempt_id"], attempt["attempt_id"])
        self.assertEqual(report["next_steps_intended_ref"], "NSI-6")
        self.assertTrue(complete_attempt(attempt, report))


if __name__ == "__main__":
    unittest.main()
