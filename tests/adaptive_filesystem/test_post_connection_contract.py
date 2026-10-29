"""Synthetic contract tests for the missing peer container and post-connection report.

This is a design-level contract, not production Adaptive Filesystem integration.
"""
from copy import deepcopy
import unittest

REQUIRED_PEER_CONTAINER = "next_steps_intended"


def validate_peer_inventory(inventory):
    """Validate top-level peer containers independently of their internal fields."""
    if not isinstance(inventory, dict):
        return {"status": "FAIL", "reason": "inventory_not_object"}
    if REQUIRED_PEER_CONTAINER not in inventory:
        return {"status": "FAIL", "reason": "required_peer_container_missing"}
    if not isinstance(inventory[REQUIRED_PEER_CONTAINER], dict):
        return {"status": "FAIL", "reason": "required_peer_container_not_object"}
    return {"status": "PASS", "reason": None}


def post_connection_report(*, source, target, outcome, evidence=None,
                           validation=None, recovered=None, differences=None,
                           changes_intended=None, changes_applied=None,
                           provenance_gaps=None, next_action=None):
    """Generate evidence for every attempt, including blocked or failed attempts."""
    return {
        "kind": "post_connection_report",
        "source": deepcopy(source),
        "target": deepcopy(target),
        "outcome": outcome,
        "evidence": deepcopy(evidence or []),
        "validation": deepcopy(validation or {"status": "UNKNOWN"}),
        "recovered": deepcopy(recovered or []),
        "differences": deepcopy(differences or []),
        "changes_intended": deepcopy(changes_intended or []),
        "changes_applied": deepcopy(changes_applied or []),
        "provenance_gaps": deepcopy(provenance_gaps or []),
        "next_action": deepcopy(next_action),
    }


def round_trip_peer_container(wave):
    """Synthetic wave → particle → report → second-wave round trip."""
    particle = {"form": "particle", "state": deepcopy(wave)}
    report = post_connection_report(
        source=particle["state"].get("source"),
        target=particle["state"].get("target"),
        outcome=particle["state"].get("outcome", "UNKNOWN"),
        evidence=particle["state"].get("evidence", []),
        changes_intended=particle["state"]["peer_inventory"][REQUIRED_PEER_CONTAINER]
            .get("changes_intended", []),
        next_action=particle["state"]["peer_inventory"][REQUIRED_PEER_CONTAINER]
            .get("next_action"),
    )
    return {"form": "wave", "association": "post_connection", "report": report}


class PostConnectionContractTests(unittest.TestCase):
    def test_01_missing_peer_container_fails_even_if_other_fields_exist(self):
        inventory = {"TERA": {}, "FIRA": {}, "WATA": {}, "purpose": "valid"}
        result = validate_peer_inventory(inventory)
        self.assertEqual(result["status"], "FAIL")
        self.assertEqual(result["reason"], "required_peer_container_missing")

    def test_02_sixth_component_is_a_peer_container_with_its_own_structure(self):
        inventory = {
            "TERA": {},
            "FIRA": {},
            "WATA": {},
            "next_steps_intended": {"changes_intended": [], "next_action": "inspect"},
        }
        self.assertEqual(validate_peer_inventory(inventory)["status"], "PASS")
        inventory["next_steps_intended"] = "inspect"
        self.assertEqual(
            validate_peer_inventory(inventory)["reason"],
            "required_peer_container_not_object",
        )

    def test_03_report_is_generated_for_success_partial_blocked_and_failed_attempts(self):
        for outcome in ("SUCCESS", "PARTIAL", "BLOCKED", "FAILED"):
            with self.subTest(outcome=outcome):
                report = post_connection_report(
                    source={"id": "source-1", "version": "v1"},
                    target={"id": "target-1", "version": "v2"},
                    outcome=outcome,
                    validation={"status": "UNKNOWN" if outcome in ("BLOCKED", "FAILED") else outcome},
                )
                self.assertEqual(report["kind"], "post_connection_report")
                self.assertEqual(report["outcome"], outcome)
                self.assertIn("validation", report)

    def test_04_planned_changes_are_not_claimed_as_applied(self):
        report = post_connection_report(
            source={"id": "s"}, target={"id": "t"}, outcome="BLOCKED",
            changes_intended=[{"id": "C1", "status": "planned"}],
            changes_applied=[],
            provenance_gaps=["target version unavailable"],
        )
        self.assertEqual(report["changes_intended"][0]["status"], "planned")
        self.assertEqual(report["changes_applied"], [])
        self.assertEqual(report["provenance_gaps"], ["target version unavailable"])

    def test_05_peer_container_survives_wave_particle_report_wave(self):
        wave = {
            "source": {"id": "source-1"},
            "target": {"id": "target-1"},
            "outcome": "PARTIAL",
            "evidence": [{"ref": "connection-log-1"}],
            "peer_inventory": {
                "next_steps_intended": {
                    "changes_intended": [{"id": "C1", "status": "planned"}],
                    "next_action": {"action": "verify", "status": "pending"},
                }
            },
        }
        result = round_trip_peer_container(wave)
        report = result["report"]
        self.assertEqual(result["form"], "wave")
        self.assertEqual(result["association"], "post_connection")
        self.assertEqual(report["source"], wave["source"])
        self.assertEqual(report["changes_intended"], [{"id": "C1", "status": "planned"}])
        self.assertEqual(report["next_action"], {"action": "verify", "status": "pending"})


if __name__ == "__main__":
    unittest.main()
