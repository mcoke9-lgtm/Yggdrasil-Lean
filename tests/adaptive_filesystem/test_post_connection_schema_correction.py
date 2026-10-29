"""Focused correction tests for WATA nesting and mandatory post-connection reports.

Scope: synthetic contract only; not production-runtime integration.
"""
import unittest

from test_recovery_roundtrip import (
    REQUIRED_COMPONENTS,
    generate_post_connection_report,
    validate_component_inventory,
)


def wata_container(next_steps_intended):
    """Intention and its implementation plan are nested inside WATA."""
    return {
        "container": "WATA",
        "next_steps_intended": {
            "intention": next_steps_intended.get("intention", {}),
            "implementation_plan": next_steps_intended.get("implementation_plan", {}),
        },
    }


def post_connection_report_for_attempt(connection):
    """A report is mandatory for every attempted connection outcome."""
    report = generate_post_connection_report(connection)
    report["source"] = connection.get("source")
    report["target"] = connection.get("target")
    report["validation_status"] = connection.get("validation_status", "UNKNOWN")
    report["provenance_gaps"] = list(connection.get("provenance_gaps", []))
    report["differences"] = list(connection.get("differences", []))
    return report


class PostConnectionSchemaCorrectionTests(unittest.TestCase):
    def test_sixth_workflow_component_is_report_generation(self):
        self.assertEqual(validate_component_inventory(REQUIRED_COMPONENTS)["status"], "VALID")
        five_components = [
            item for item in REQUIRED_COMPONENTS
            if item["name"] != "post_connection_report"
        ]
        result = validate_component_inventory(five_components)
        self.assertEqual(result["status"], "INVALID")
        self.assertIn("post_connection_report", result["missing"])

    def test_next_steps_intended_is_inside_wata_not_a_peer_container(self):
        value = wata_container({
            "intention": {"change": "revise label", "status": "intended"},
            "implementation_plan": {"next_action": "inspect evidence"},
        })
        self.assertEqual(value["container"], "WATA")
        self.assertIn("next_steps_intended", value)
        self.assertNotIn("next_steps_intended", ["TERA", "FIRA", "WATA", "AIRA"])
        self.assertIn("implementation_plan", value["next_steps_intended"])
        self.assertIn("next_action", value["next_steps_intended"]["implementation_plan"])
        self.assertNotIn("next_action", value["next_steps_intended"])

    def test_report_is_generated_for_every_connection_outcome(self):
        outcomes = ("SUCCESS", "PARTIAL", "BLOCKED", "FAIL", "UNKNOWN")
        for outcome in outcomes:
            with self.subTest(outcome=outcome):
                report = post_connection_report_for_attempt({
                    "connection_id": "conn-" + outcome.lower(),
                    "attempted": True,
                    "outcome": outcome,
                    "source": {"id": "src", "version": "v1"},
                    "target": {"id": "dst", "version": "v2"},
                    "validation_status": "UNKNOWN" if outcome in ("BLOCKED", "UNKNOWN") else outcome,
                    "evidence": [],
                    "changes_intended": [{"change_id": "C1", "status": "planned"}],
                    "changes_applied": [],
                    "unknowns": ["target_unavailable"] if outcome in ("BLOCKED", "UNKNOWN") else [],
                    "provenance_gaps": [],
                    "differences": [],
                })
                self.assertEqual(report["form"], "post_connection_report")
                self.assertEqual(report["outcome"], outcome)
                self.assertEqual(report["connection_id"], "conn-" + outcome.lower())
                self.assertEqual(report["source"]["id"], "src")
                self.assertEqual(report["target"]["id"], "dst")
                self.assertEqual(report["changes_intended"][0]["status"], "planned")
                self.assertEqual(report["changes_applied"], [])
                if outcome in ("BLOCKED", "UNKNOWN"):
                    self.assertEqual(report["validation_status"], "UNKNOWN")
                    self.assertNotEqual(report["outcome"], "SUCCESS")

    def test_report_keeps_unknown_and_provenance_gaps_visible(self):
        report = post_connection_report_for_attempt({
            "connection_id": "conn-gap",
            "outcome": "BLOCKED",
            "validation_status": "UNKNOWN",
            "provenance_gaps": ["source_version_unavailable"],
            "differences": ["relationship_status_unresolved"],
        })
        self.assertEqual(report["validation_status"], "UNKNOWN")
        self.assertIn("source_version_unavailable", report["provenance_gaps"])
        self.assertIn("relationship_status_unresolved", report["differences"])


if __name__ == "__main__":
    unittest.main()
