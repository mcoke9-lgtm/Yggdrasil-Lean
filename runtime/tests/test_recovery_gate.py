import unittest

from adaptive_filesystem_runtime.alternative_work import WorkRequest, evaluate_work
from adaptive_filesystem_runtime.recovery import RecoveryFacts, decide_recovery
from adaptive_filesystem_runtime.recovery_gate import gate_retry


def work():
    return evaluate_work(WorkRequest(
        request_id="req-1", active_branch="branch-a", checkpoint_id="AF-PILOT-006",
        current_goal="continue safely", blocked_operation="external write",
        observed_failure="acknowledgement lost", blocked=True,
        retry_requested=True, retry_material_change=True, prior_retry_count=0,
        retry_budget=1, operation_fingerprint="input-v2",
        prior_operation_fingerprints=("input-v1",),
    ))


def recovery(**overrides):
    values = dict(
        operation_id="op-1", attempt_id="attempt-1", action_class="external-write",
        durable_intent_recorded=True, external_action_started=True,
        outcome_evidence=None, idempotent_external_action=False,
        idempotency_key_supported_by_target=False,
    )
    values.update(overrides)
    return decide_recovery(RecoveryFacts(**values))


class RecoveryGateIntegrationTests(unittest.TestCase):
    def test_uncertain_non_idempotent_action_blocks_retry(self):
        result = gate_retry(work(), recovery())
        self.assertEqual(result.outcome, "BLOCK_RETRY")
        self.assertFalse(result.retry_allowed)
        self.assertEqual(result.recovery_outcome, "RECONCILE_REQUIRED")

    def test_target_enforced_idempotency_allows_retry(self):
        result = gate_retry(work(), recovery(
            idempotent_external_action=True,
            idempotency_key_supported_by_target=True,
        ))
        self.assertEqual(result.outcome, "ALLOW_RETRY")
        self.assertTrue(result.retry_allowed)

    def test_confirmed_completion_blocks_retry(self):
        result = gate_retry(work(), recovery(outcome_evidence="receipt://confirmed"))
        self.assertEqual(result.outcome, "BLOCK_RETRY")

    def test_conflicting_recovery_state_blocks_retry(self):
        result = gate_retry(work(), recovery(
            explicit_reconciliation_result="completed",
        ))
        self.assertEqual(result.outcome, "BLOCK_RETRY")

    def test_no_retry_recommendation_does_not_become_authorization(self):
        no_retry = evaluate_work(WorkRequest(
            request_id="req-2", active_branch="branch-a", checkpoint_id="AF-PILOT-006",
            current_goal="continue safely", blocked_operation="external write",
            observed_failure="unknown", blocked=True,
        ))
        result = gate_retry(no_retry, recovery())
        self.assertEqual(result.outcome, "NO_RETRY_RECOMMENDED")
        self.assertFalse(result.retry_allowed)


if __name__ == "__main__":
    unittest.main()
