"""Tests for interrupted transfer classification and safe resume."""
import unittest
from copy import deepcopy

from src.adaptive_filesystem.transfer_recovery import inspect_transfer, resume_transfer


class TransferRecoveryTests(unittest.TestCase):
    def setUp(self):
        self.source = [
            {"record_id": "A", "value": "root", "provenance": "fixture"},
            {"record_id": "B", "value": "leaf", "provenance": "fixture"},
            {"record_id": "C", "value": "branch", "provenance": "fixture"},
        ]
        self.partial = [deepcopy(self.source[0]), deepcopy(self.source[1])]

    def test_partial_receipt_identifies_missing_record(self):
        report = inspect_transfer(self.source, self.partial)
        self.assertEqual(report["status"], "INTERRUPTED")
        self.assertEqual(report["missing_record_ids"], ["C"])

    def test_partial_receipt_does_not_claim_verification(self):
        report = inspect_transfer(self.source, self.partial)
        self.assertEqual(report["verification_status"], "UNVERIFIED")

    def test_resume_adds_only_missing_records(self):
        result = resume_transfer(self.source, self.partial)
        self.assertEqual(result["recovery_status"], "RESUMED")
        self.assertEqual(result["transfer_report"]["status"], "COMPLETE")
        self.assertEqual(len(result["records"]), 3)

    def test_resume_is_idempotent(self):
        first = resume_transfer(self.source, self.partial)
        second = resume_transfer(self.source, first["records"])
        self.assertEqual(second["recovery_status"], "UNCHANGED")
        self.assertEqual(second["records"], first["records"])

    def test_source_and_partial_inputs_are_not_mutated(self):
        source_before = deepcopy(self.source)
        partial_before = deepcopy(self.partial)
        resume_transfer(self.source, self.partial)
        self.assertEqual(self.source, source_before)
        self.assertEqual(self.partial, partial_before)

    def test_conflicting_received_record_blocks_resume(self):
        conflicting = deepcopy(self.partial)
        conflicting[0]["value"] = "rewritten"
        report = inspect_transfer(self.source, conflicting)
        self.assertEqual(report["status"], "CONFLICT")
        with self.assertRaises(ValueError):
            resume_transfer(self.source, conflicting)

    def test_unexpected_received_record_blocks_resume(self):
        unexpected = deepcopy(self.partial) + [
            {"record_id": "X", "value": "unknown", "provenance": "unknown"}
        ]
        report = inspect_transfer(self.source, unexpected)
        self.assertEqual(report["status"], "CONFLICT")
        with self.assertRaises(ValueError):
            resume_transfer(self.source, unexpected)

    def test_duplicate_received_identity_is_rejected(self):
        duplicate = deepcopy(self.partial) + [deepcopy(self.partial[0])]
        with self.assertRaisesRegex(ValueError, "duplicate_received_record"):
            inspect_transfer(self.source, duplicate)

    def test_duplicate_source_identity_is_rejected(self):
        duplicate_source = deepcopy(self.source) + [deepcopy(self.source[0])]
        with self.assertRaisesRegex(ValueError, "duplicate_source_record"):
            inspect_transfer(duplicate_source, self.partial)


if __name__ == "__main__":
    unittest.main()
