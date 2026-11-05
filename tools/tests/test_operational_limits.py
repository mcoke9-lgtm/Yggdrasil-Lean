import importlib.util
import unittest
from datetime import date
from pathlib import Path

SPEC = importlib.util.spec_from_file_location(
    "operational_gate", Path(__file__).parents[1] / "check_operational_limits.py"
)
gate = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(gate)


class OperationalGateTests(unittest.TestCase):
    def setUp(self):
        self.register = {"constraints": [{
            "capability": "Create scheduled tasks / automations",
            "scope": "Current account",
            "state": "blocked",
            "maximum_capacity": 3,
            "current_usage": 3,
            "remaining_capacity": 0,
            "unit": "slots",
            "evidence_source": "Test fixture",
            "observed_at": "2026-11-05",
            "safe_alternative": "Manual one-time review",
            "actions_not_authorized": ["Delete or pause an existing task"]
        }]}
        self.today = date(2026, 11, 5)

    def test_fresh_block_is_blocked(self):
        result, code = gate.evaluate(self.register, "Create scheduled tasks / automations", as_of=self.today)
        self.assertEqual((result["decision"], code), ("blocked", 2))
        self.assertEqual(result["remaining_capacity"], 0)

    def test_unknown_capability_requires_recheck(self):
        result, code = gate.evaluate(self.register, "Create cloud compute", as_of=self.today)
        self.assertEqual((result["decision"], code), ("recheck_required", 3))

    def test_stale_evidence_requires_recheck(self):
        result, code = gate.evaluate(self.register, "Create scheduled tasks / automations",
                                     as_of=date(2026, 11, 20), max_age_days=7)
        self.assertEqual((result["decision"], code), ("recheck_required", 3))

    def test_explicitly_unauthorized_action_is_gated(self):
        result, code = gate.evaluate(self.register, "Create scheduled tasks / automations",
                                     action="Delete or pause an existing task", as_of=self.today)
        self.assertEqual((result["decision"], code), ("authorization_required", 4))

    def test_constrained_capability_can_proceed_with_conditions(self):
        self.register["constraints"][0]["state"] = "constrained"
        result, code = gate.evaluate(self.register, "Create scheduled tasks / automations", as_of=self.today)
        self.assertEqual((result["decision"], code), ("proceed_with_constraints", 0))

    def test_available_capability_can_proceed(self):
        self.register["constraints"][0]["state"] = "available"
        result, code = gate.evaluate(self.register, "Create scheduled tasks / automations", as_of=self.today)
        self.assertEqual((result["decision"], code), ("proceed", 0))


if __name__ == "__main__":
    unittest.main()
