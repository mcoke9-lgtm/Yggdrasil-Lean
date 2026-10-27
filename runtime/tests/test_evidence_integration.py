import unittest

from adaptive_filesystem_runtime.evidence_adapter import EvidenceVerificationResult
from adaptive_filesystem_runtime.evidence_integration import decide_recovery_with_adapter
from adaptive_filesystem_runtime.recovery import RecoveryFacts


def facts(**overrides):
    values = dict(
        operation_id="op-1", attempt_id="a-1", action_class="external-write",
        durable_intent_recorded=True, external_action_started=True,
        outcome_evidence="receipt://1", idempotent_external_action=False,
        idempotency_key_supported_by_target=False,
        explicit_reconciliation_result=None,
    )
    values.update(overrides)
    return RecoveryFacts(**values)


class FakeAdapter:
    def __init__(self, result=None, error=None):
        self.result, self.error, self.calls = result, error, []

    def verify_evidence(self, reference, claim):
        self.calls.append((reference, claim))
        if self.error:
            raise self.error
        return self.result


class EvidenceIntegrationTests(unittest.TestCase):
    def verified(self):
        return EvidenceVerificationResult(
            "VERIFIED", "OUTCOME", "receipt://1", "source://1",
            "verifier-1", "verification://1",
        )

    def test_verified_adapter_result_can_confirm_completion(self):
        adapter = FakeAdapter(self.verified())
        result = decide_recovery_with_adapter(facts(), adapter)
        self.assertEqual(result.outcome, "CONFIRMED_COMPLETE")
        self.assertEqual(adapter.calls, [("receipt://1", "OUTCOME")])

    def test_unavailable_requires_reconciliation(self):
        adapter = FakeAdapter(EvidenceVerificationResult(
            "UNAVAILABLE", "OUTCOME", "receipt://1", reason="timeout"))
        self.assertEqual(decide_recovery_with_adapter(facts(), adapter).outcome,
                         "RECONCILE_REQUIRED")

    def test_invalid_result_stops_with_conflict(self):
        adapter = FakeAdapter(EvidenceVerificationResult(
            "INVALID", "OUTCOME", "receipt://1", reason="rejected"))
        self.assertEqual(decide_recovery_with_adapter(facts(), adapter).outcome,
                         "STOP_CONFLICT")

    def test_reference_mismatch_is_rejected(self):
        adapter = FakeAdapter(EvidenceVerificationResult(
            "VERIFIED", "OUTCOME", "receipt://other", "source://1",
            "verifier-1", "verification://1"))
        self.assertEqual(decide_recovery_with_adapter(facts(), adapter).outcome,
                         "STOP_CONFLICT")

    def test_missing_verified_metadata_is_rejected(self):
        adapter = FakeAdapter(EvidenceVerificationResult(
            "VERIFIED", "OUTCOME", "receipt://1"))
        self.assertEqual(decide_recovery_with_adapter(facts(), adapter).outcome,
                         "STOP_CONFLICT")

    def test_adapter_exception_does_not_confirm_completion(self):
        adapter = FakeAdapter(error=TimeoutError("offline"))
        self.assertEqual(decide_recovery_with_adapter(facts(), adapter).outcome,
                         "RECONCILE_REQUIRED")

    def test_no_outcome_reference_does_not_call_adapter(self):
        adapter = FakeAdapter(self.verified())
        result = decide_recovery_with_adapter(facts(outcome_evidence=None), adapter)
        self.assertEqual(result.outcome, "RECONCILE_REQUIRED")
        self.assertEqual(adapter.calls, [])

    def test_unknown_status_is_rejected(self):
        adapter = FakeAdapter(EvidenceVerificationResult(
            "MAYBE", "OUTCOME", "receipt://1", "source://1",
            "verifier-1", "verification://1"))
        self.assertEqual(decide_recovery_with_adapter(facts(), adapter).outcome,
                         "STOP_CONFLICT")


if __name__ == "__main__":
    unittest.main()
