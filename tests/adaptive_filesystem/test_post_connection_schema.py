"""Synthetic contract tests for the six-part recovery checkpoint.

Scope: design-contract checks only. They do not establish production runtime integration.
Production and ownership are always TERA-associated.
"""
import unittest

REQUIRED_COMPONENTS = (
    "purpose",
    "branch",
    "state",
    "blockers",
    "next_action",
    "post_connection_report",
)

REPORT_STATUSES = {
    "GENERATED_VERIFIED",
    "GENERATED_UNVERIFIED",
    "FAILED",
    "NOT_GENERATED",
    "UNKNOWN",
}


def validate_checkpoint(checkpoint):
    if not isinstance(checkpoint, dict):
        return {"status": "INVALID", "missing": list(REQUIRED_COMPONENTS)}
    missing = [key for key in REQUIRED_COMPONENTS if key not in checkpoint]
    if missing:
        return {"status": "INVALID", "missing": missing}
    report = checkpoint["post_connection_report"]
    if not isinstance(report, dict):
        return {"status": "INVALID", "missing": [] , "reason": "report_not_object"}
    if report.get("status") not in REPORT_STATUSES:
        return {"status": "INVALID", "missing": [], "reason": "report_status_invalid"}
    return {"status": "VALID", "missing": []}


def validate_tera_ownership(connection):
    """Production and ownership must remain attached to TERA, not migrate by stage."""
    issues = []
    for key in ("production", "ownership"):
        value = connection.get(key)
        if not isinstance(value, dict) or value.get("layer") != "TERA":
            issues.append(key + "_must_be_tera")
    return {"status": "VALID" if not issues else "INVALID", "issues": issues}


def connection_report(attempt):
    """Synthetic reporting contract: every completed attempt yields a report."""
    outcome = attempt.get("outcome", "UNKNOWN")
    evidence = attempt.get("evidence", [])
    status = "GENERATED_VERIFIED" if evidence and attempt.get("verified") is True else "GENERATED_UNVERIFIED"
    if outcome in ("FAILED", "BLOCKED", "UNKNOWN") and not evidence:
        status = "GENERATED_UNVERIFIED"
    return {
        "report_id": attempt.get("report_id", "UNKNOWN"),
        "attempt_id": attempt.get("attempt_id", "UNKNOWN"),
        "source": attempt.get("source", "UNKNOWN"),
        "destination": attempt.get("destination", "UNKNOWN"),
        "outcome": outcome,
        "evidence": list(evidence),
        "discrepancies": list(attempt.get("discrepancies", [])),
        "unresolved": list(attempt.get("unresolved", [])),
        "intended_vs_actual": attempt.get("intended_vs_actual", "UNKNOWN"),
        "status": status,
    }


def valid_checkpoint():
    return {
        "purpose": "recover connection state",
        "branch": {"source": "src", "destination": "dst"},
        "state": {"observed": "baseline"},
        "blockers": [],
        "next_action": "review report",
        "post_connection_report": {
            "status": "GENERATED_UNVERIFIED",
            "report_id": "R-001",
        },
        # Cross-cutting metadata, not a replacement for the report.
        "changes_intended": [{"change_id": "C-001", "status": "planned"}],
    }


class PostConnectionSchemaTests(unittest.TestCase):
    def test_01_checkpoint_requires_exact_six_named_components(self):
        checkpoint = valid_checkpoint()
        result = validate_checkpoint(checkpoint)
        self.assertEqual(result["status"], "VALID")
        self.assertEqual(len(REQUIRED_COMPONENTS), 6)

    def test_02_changes_intended_does_not_substitute_for_report(self):
        checkpoint = valid_checkpoint()
        del checkpoint["post_connection_report"]
        result = validate_checkpoint(checkpoint)
        self.assertEqual(result["status"], "INVALID")
        self.assertIn("post_connection_report", result["missing"])

    def test_03_successful_attempt_produces_a_report(self):
        report = connection_report({
            "attempt_id": "A-1", "source": "S-1", "destination": "D-1",
            "outcome": "SUCCESS", "evidence": ["E-1"], "verified": True,
        })
        self.assertEqual(report["status"], "GENERATED_VERIFIED")
        self.assertEqual(report["attempt_id"], "A-1")

    def test_04_failed_or_blocked_attempt_still_produces_a_report(self):
        for outcome in ("FAILED", "BLOCKED"):
            with self.subTest(outcome=outcome):
                report = connection_report({"attempt_id": "A-2", "outcome": outcome})
                self.assertEqual(report["outcome"], outcome)
                self.assertTrue(report["report_id"])
                self.assertNotEqual(report["status"], "GENERATED_VERIFIED")

    def test_05_unverified_report_is_not_verified_by_default(self):
        report = connection_report({
            "attempt_id": "A-3", "outcome": "SUCCESS", "evidence": ["E-3"]
        })
        self.assertEqual(report["status"], "GENERATED_UNVERIFIED")

    def test_06_production_and_ownership_are_tera_associated(self):
        connection = {
            "production": {"layer": "TERA", "id": "P-1"},
            "ownership": {"layer": "TERA", "owner": "roots"},
        }
        self.assertEqual(validate_tera_ownership(connection)["status"], "VALID")
        connection["ownership"]["layer"] = "FIRA"
        result = validate_tera_ownership(connection)
        self.assertEqual(result["status"], "INVALID")
        self.assertIn("ownership_must_be_tera", result["issues"])

    def test_07_intended_changes_remain_distinct_from_actual_outcome(self):
        checkpoint = valid_checkpoint()
        self.assertEqual(checkpoint["changes_intended"][0]["status"], "planned")
        report = connection_report({
            "attempt_id": "A-4", "outcome": "SUCCESS",
            "intended_vs_actual": {"planned": "label revision", "actual": "unchanged"},
        })
        self.assertEqual(report["intended_vs_actual"]["actual"], "unchanged")


if __name__ == "__main__":
    unittest.main()
