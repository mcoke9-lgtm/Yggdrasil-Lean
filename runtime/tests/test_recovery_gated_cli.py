import io
import json
import unittest
from contextlib import redirect_stderr, redirect_stdout
from unittest.mock import patch

from adaptive_filesystem_runtime.cli import main


def payload():
    return {
        "request": {
            "request_id": "req-1", "active_branch": "branch-a",
            "checkpoint_id": "AF-PILOT-006", "current_goal": "recover safely",
            "blocked_operation": "external write", "observed_failure": "ack lost",
            "blocked": True, "retry_requested": True, "retry_material_change": True,
            "prior_retry_count": 0, "retry_budget": 1, "operation_fingerprint": "v2",
            "prior_operation_fingerprints": ["v1"], "candidates": [],
        },
        "recovery_facts": {
            "operation_id": "op-1", "attempt_id": "attempt-1",
            "action_class": "external-write", "durable_intent_recorded": True,
            "external_action_started": True, "outcome_evidence": None,
            "idempotent_external_action": False,
            "idempotency_key_supported_by_target": False,
        },
    }


class RecoveryGatedCliTests(unittest.TestCase):
    def invoke(self, value):
        out, err = io.StringIO(), io.StringIO()
        with patch("sys.stdin", io.StringIO(json.dumps(value))), redirect_stdout(out), redirect_stderr(err):
            code = main(["recover-evaluate"])
        return code, out.getvalue(), err.getvalue()

    def test_uncertain_action_stays_blocked_in_json_report(self):
        code, output, error = self.invoke(payload())
        report = json.loads(output)
        self.assertEqual(code, 0, error)
        self.assertEqual(report["schema_version"], "af.recovery-gated-work.v1")
        self.assertEqual(report["work_decision"]["outcome"], "RETRY_JUSTIFIED")
        self.assertEqual(report["recovery_decision"]["outcome"], "RECONCILE_REQUIRED")
        self.assertEqual(report["retry_gate"]["outcome"], "BLOCK_RETRY")
        self.assertFalse(report["retry_gate"]["retry_allowed"])
        self.assertEqual(report["execution_status"], "not_attempted")
        self.assertEqual(report["persistence_status"], "not_persisted")

    def test_same_facts_in_fresh_invocation_retain_block(self):
        first = json.loads(self.invoke(payload())[1])
        second = json.loads(self.invoke(payload())[1])
        self.assertEqual(first["retry_gate"], second["retry_gate"])
        self.assertFalse(second["retry_gate"]["retry_allowed"])

    def test_idempotent_target_can_be_reported_as_replay_safe(self):
        data = payload()
        data["recovery_facts"]["idempotent_external_action"] = True
        data["recovery_facts"]["idempotency_key_supported_by_target"] = True
        report = json.loads(self.invoke(data)[1])
        self.assertEqual(report["retry_gate"]["outcome"], "ALLOW_RETRY")
        self.assertTrue(report["retry_gate"]["retry_allowed"])
        self.assertEqual(report["execution_status"], "not_attempted")

    def test_malformed_recovery_facts_fail_closed(self):
        data = payload()
        data["recovery_facts"]["external_action_started"] = "unknown"
        report = json.loads(self.invoke(data)[1])
        self.assertEqual(report["recovery_decision"]["outcome"], "STOP_CONFLICT")
        self.assertFalse(report["retry_gate"]["retry_allowed"])


if __name__ == "__main__":
    unittest.main()
