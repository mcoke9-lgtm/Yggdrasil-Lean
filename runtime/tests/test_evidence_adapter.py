import unittest

from adaptive_filesystem_runtime.evidence_adapter import (
    AuthoritativeEvidenceAdapter, EvidenceVerificationResult, validate_adapter_result,
)


class FakeEvidenceAdapter:
    def __init__(self, result):
        self.result = result
        self.calls = []

    def verify_evidence(self, evidence_reference, claim):
        self.calls.append((evidence_reference, claim))
        return self.result


class EvidenceAdapterContractTests(unittest.TestCase):
    def test_protocol_and_explicit_unavailable_result(self):
        result = EvidenceVerificationResult("UNAVAILABLE", "OUTCOME", "receipt://1", reason="source unavailable")
        adapter = FakeEvidenceAdapter(result)
        self.assertIsInstance(adapter, AuthoritativeEvidenceAdapter)
        self.assertEqual(adapter.verify_evidence("receipt://1", "OUTCOME").status, "UNAVAILABLE")

    def test_verified_requires_source_verifier_and_record(self):
        result = validate_adapter_result(
            EvidenceVerificationResult("VERIFIED", "OUTCOME", "receipt://1", "source://1", "verifier-1", "verification://1"),
            expected_reference="receipt://1", expected_claim="OUTCOME")
        self.assertEqual(result.status, "VERIFIED")

    def test_missing_metadata_fails_closed(self):
        result = validate_adapter_result(
            EvidenceVerificationResult("VERIFIED", "OUTCOME", "receipt://1"),
            expected_reference="receipt://1", expected_claim="OUTCOME")
        self.assertEqual(result.status, "INVALID")

    def test_reference_mismatch_fails_closed(self):
        result = validate_adapter_result(
            EvidenceVerificationResult("VERIFIED", "OUTCOME", "receipt://other", "source://1", "v", "verification://1"),
            expected_reference="receipt://1", expected_claim="OUTCOME")
        self.assertEqual(result.status, "INVALID")

    def test_claim_mismatch_fails_closed(self):
        result = validate_adapter_result(
            EvidenceVerificationResult("VERIFIED", "RECONCILIATION", "receipt://1", "source://1", "v", "verification://1"),
            expected_reference="receipt://1", expected_claim="OUTCOME")
        self.assertEqual(result.status, "INVALID")

    def test_unavailable_is_not_promoted(self):
        result = validate_adapter_result(
            EvidenceVerificationResult("UNAVAILABLE", "OUTCOME", "receipt://1", reason="timeout"),
            expected_reference="receipt://1", expected_claim="OUTCOME")
        self.assertEqual(result.status, "UNAVAILABLE")


if __name__ == "__main__":
    unittest.main()
