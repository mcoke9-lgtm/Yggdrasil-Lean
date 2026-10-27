import unittest

from adaptive_filesystem_runtime.recovery import RecoveryFacts
from adaptive_filesystem_runtime.evidence_provenance import (
    EvidenceProvenance, decide_recovery_with_provenance,
)


def facts(**overrides):
    values = dict(
        operation_id="op-1", attempt_id="a-1", action_class="external-write",
        durable_intent_recorded=True, external_action_started=True,
        outcome_evidence="receipt://1", idempotent_external_action=False,
        idempotency_key_supported_by_target=False,
        explicit_reconciliation_result=None,
        reconciliation_evidence_reference=None,
    )
    values.update(overrides)
    return RecoveryFacts(**values)


def provenance(**overrides):
    values = dict(
        claim="OUTCOME", status="VERIFIED",
        evidence_reference="receipt://1", verifier="trusted-adapter",
        verification_reference="verification://receipt-1",
    )
    values.update(overrides)
    return EvidenceProvenance(**values)


class EvidenceProvenanceTests(unittest.TestCase):
    def test_verified_outcome_preserves_completion_decision(self):
        result = decide_recovery_with_provenance(facts(), provenance())
        self.assertEqual(result.outcome, "CONFIRMED_COMPLETE")

    def test_caller_assertion_cannot_confirm_completion(self):
        result = decide_recovery_with_provenance(
            facts(), provenance(status="CALLER_ASSERTED", verifier=None, verification_reference=None)
        )
        self.assertEqual(result.outcome, "STOP_CONFLICT")

    def test_missing_provenance_cannot_confirm_completion(self):
        result = decide_recovery_with_provenance(
            facts(), EvidenceProvenance(claim="NONE", status="ABSENT")
        )
        self.assertEqual(result.outcome, "STOP_CONFLICT")

    def test_verified_status_requires_verifier(self):
        result = decide_recovery_with_provenance(facts(), provenance(verifier=" "))
        self.assertEqual(result.outcome, "STOP_CONFLICT")

    def test_verified_status_requires_verification_record(self):
        result = decide_recovery_with_provenance(facts(), provenance(verification_reference=None))
        self.assertEqual(result.outcome, "STOP_CONFLICT")

    def test_verified_reference_must_match_exact_outcome_reference(self):
        result = decide_recovery_with_provenance(
            facts(), provenance(evidence_reference="receipt://different")
        )
        self.assertEqual(result.outcome, "STOP_CONFLICT")

    def test_verified_claim_must_match_facts(self):
        result = decide_recovery_with_provenance(facts(outcome_evidence=None), provenance())
        self.assertEqual(result.outcome, "STOP_CONFLICT")

    def test_invalid_evidence_fails_closed(self):
        result = decide_recovery_with_provenance(facts(), provenance(status="INVALID"))
        self.assertEqual(result.outcome, "STOP_CONFLICT")

    def test_unverified_evidence_still_requires_reconciliation(self):
        result = decide_recovery_with_provenance(
            facts(outcome_evidence=None),
            provenance(status="CALLER_ASSERTED", claim="NONE",
                       evidence_reference="note://caller", verifier=None,
                       verification_reference=None),
        )
        self.assertEqual(result.outcome, "RECONCILE_REQUIRED")

    def test_verified_reconciliation_requires_exact_reference_match(self):
        result = decide_recovery_with_provenance(
            facts(explicit_reconciliation_result="completed",
                  reconciliation_evidence_reference="reconcile://1"),
            provenance(claim="RECONCILIATION", evidence_reference="reconcile://other"),
        )
        self.assertEqual(result.outcome, "STOP_CONFLICT")

    def test_verified_reconciliation_requires_matching_claim(self):
        result = decide_recovery_with_provenance(
            facts(explicit_reconciliation_result=None),
            provenance(claim="RECONCILIATION"),
        )
        self.assertEqual(result.outcome, "STOP_CONFLICT")


if __name__ == "__main__":
    unittest.main()
