import unittest

from adaptive_filesystem_runtime.recovery import RecoveryFacts, decide_recovery


def facts(**overrides):
    values = dict(
        operation_id="op-1", attempt_id="attempt-1", action_class="external-write",
        durable_intent_recorded=True, external_action_started=True,
        outcome_evidence=None, idempotent_external_action=False,
        idempotency_key_supported_by_target=False,
        explicit_reconciliation_result=None,
    )
    values.update(overrides)
    return RecoveryFacts(**values)


class RecoveryDecisionTests(unittest.TestCase):
    def test_action_may_have_happened_requires_reconciliation(self):
        self.assertEqual(decide_recovery(facts()).outcome, "RECONCILE_REQUIRED")

    def test_idempotent_target_with_same_key_allows_replay(self):
        self.assertEqual(decide_recovery(facts(
            idempotent_external_action=True,
            idempotency_key_supported_by_target=True,
        )).outcome, "SAFE_TO_REPLAY")

    def test_idempotency_claim_without_target_support_is_not_enough(self):
        self.assertEqual(decide_recovery(facts(
            idempotent_external_action=True,
            idempotency_key_supported_by_target=False,
        )).outcome, "RECONCILE_REQUIRED")

    def test_evidence_confirms_completion(self):
        self.assertEqual(decide_recovery(facts(
            outcome_evidence="receipt://confirmed",
        )).outcome, "CONFIRMED_COMPLETE")

    def test_durable_intent_without_external_start_can_start(self):
        self.assertEqual(decide_recovery(facts(
            external_action_started=False,
        )).outcome, "SAFE_TO_START")

    def test_missing_durable_intent_but_possible_start_requires_reconciliation(self):
        self.assertEqual(decide_recovery(facts(
            durable_intent_recorded=False,
        )).outcome, "RECONCILE_REQUIRED")

    def test_reconciliation_can_confirm_not_started(self):
        self.assertEqual(decide_recovery(facts(
            external_action_started=False,
            explicit_reconciliation_result="not_started",
        )).outcome, "SAFE_TO_START")

    def test_completion_without_evidence_fails_closed(self):
        self.assertEqual(decide_recovery(facts(
            explicit_reconciliation_result="completed",
        )).outcome, "STOP_CONFLICT")

    def test_invalid_facts_fail_closed(self):
        self.assertEqual(decide_recovery(facts(operation_id=" ")).outcome, "STOP_CONFLICT")


if __name__ == "__main__":
    unittest.main()
