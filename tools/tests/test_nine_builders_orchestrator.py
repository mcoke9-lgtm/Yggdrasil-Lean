import sys
import unittest
from datetime import date
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parents[1]))
from nine_builders_orchestrator import (
    BUILDER_ROLES, prepare_builder_cycle, dispatch_builder_action
)


class NineBuildersOrchestrationTests(unittest.TestCase):
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

    def test_all_nine_roles_receive_same_blocking_decision(self):
        cycle = prepare_builder_cycle(self.register, "Create scheduled tasks / automations",
                                      as_of=self.today)
        self.assertEqual(len(BUILDER_ROLES), 9)
        self.assertFalse(cycle["dispatch_permitted"])
        for role in BUILDER_ROLES:
            self.assertTrue(cycle["role_contexts"][role]["must_honor_shared_gate"])
            self.assertEqual(cycle["role_contexts"][role]["shared_gate_decision"]["decision"], "blocked")

    def test_blocked_action_is_never_dispatched(self):
        cycle = prepare_builder_cycle(self.register, "Create scheduled tasks / automations",
                                      as_of=self.today)
        calls = []
        result = dispatch_builder_action(cycle, lambda: calls.append("ran"))
        self.assertFalse(result["dispatched"])
        self.assertEqual(calls, [])

    def test_unknown_capability_is_fail_closed(self):
        cycle = prepare_builder_cycle(self.register, "Unlisted capability", as_of=self.today)
        self.assertEqual(cycle["gate_decision"]["decision"], "recheck_required")
        self.assertFalse(cycle["dispatch_permitted"])

    def test_fresh_available_capability_dispatches(self):
        self.register["constraints"][0]["state"] = "available"
        cycle = prepare_builder_cycle(self.register, "Create scheduled tasks / automations",
                                      as_of=self.today)
        result = dispatch_builder_action(cycle, lambda: "completed")
        self.assertTrue(result["dispatched"])
        self.assertEqual(result["result"], "completed")

    def test_separate_authorization_is_required_when_declared(self):
        self.register["constraints"][0]["state"] = "available"
        cycle = prepare_builder_cycle(self.register, "Create scheduled tasks / automations",
                                      as_of=self.today)
        calls = []
        result = dispatch_builder_action(
            cycle, lambda: calls.append("ran"),
            requires_explicit_authorization=True, authorization_granted=False
        )
        self.assertEqual(result["decision"], "authorization_required")
        self.assertEqual(calls, [])

    def test_explicit_authorization_allows_gate_approved_action(self):
        self.register["constraints"][0]["state"] = "available"
        cycle = prepare_builder_cycle(self.register, "Create scheduled tasks / automations",
                                      as_of=self.today)
        result = dispatch_builder_action(
            cycle, lambda: "authorized action",
            requires_explicit_authorization=True, authorization_granted=True
        )
        self.assertTrue(result["dispatched"])
        self.assertEqual(result["result"], "authorized action")


if __name__ == "__main__":
    unittest.main()
