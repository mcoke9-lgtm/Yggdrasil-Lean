"""Contract tests for durable external-effect intent and receipt handling."""
import tempfile
import unittest
from pathlib import Path

from adaptive_filesystem.effect_receipts import SQLiteEffectReceiptStore


class EffectReceiptTests(unittest.TestCase):
    def setUp(self):
        self.tempdir = tempfile.TemporaryDirectory()
        self.store = SQLiteEffectReceiptStore(Path(self.tempdir.name) / "effects.sqlite")
        self.args = {
            "idempotency_key": "effect-auth-9",
            "authorization_id": "auth-9",
            "action_fingerprint": "sha256:abc",
            "environment_id": "repo-prod",
            "environment_action": "write:branch-ref",
        }

    def tearDown(self):
        self.tempdir.cleanup()

    def intent(self):
        return self.store.register_intent(**self.args)

    def receipt(self, **overrides):
        values = {
            **self.args,
            "external_receipt_id": "remote-op-77",
            "outcome": {"commit": "abc123", "result": "applied"},
        }
        values.update(overrides)
        return self.store.record_receipt(**values)

    def test_ER01_intent_is_persisted_before_external_effect(self):
        result = self.intent()
        self.assertEqual(result["status"], "INTENDED")
        self.assertEqual(self.store.inspect(self.args["idempotency_key"])["status"], "INTENDED")

    def test_ER02_exact_intent_retry_is_idempotent(self):
        self.intent()
        self.assertEqual(self.intent(), {"status": "REPLAY", "state": "INTENDED"})

    def test_ER03_reused_idempotency_key_with_different_binding_rejects(self):
        self.intent()
        result = self.store.register_intent(**{
            **self.args, "environment_action": "delete:repository"
        })
        self.assertEqual(result, {"status": "REJECT", "reason": "idempotency_key_conflict"})

    def test_ER04_unknown_intent_cannot_accept_receipt(self):
        result = self.receipt()
        self.assertEqual(result, {"status": "REJECT", "reason": "intent_unknown"})

    def test_ER05_receipt_must_match_all_action_bindings(self):
        self.intent()
        result = self.receipt(environment_id="staging")
        self.assertEqual(result, {"status": "REJECT", "reason": "receipt_binding_mismatch"})

    def test_ER06_matching_receipt_is_durably_recorded(self):
        self.intent()
        result = self.receipt()
        self.assertEqual(result["status"], "RECEIVED")
        state = self.store.inspect(self.args["idempotency_key"])
        self.assertEqual(state["external_receipt_id"], "remote-op-77")
        self.assertTrue(state["outcome_digest"].startswith("sha256:"))

    def test_ER07_identical_receipt_retry_returns_replay(self):
        self.intent()
        first = self.receipt()
        retry = self.receipt()
        self.assertEqual(first["status"], "RECEIVED")
        self.assertEqual(retry, {"status": "REPLAY", "external_receipt_id": "remote-op-77"})

    def test_ER08_conflicting_second_receipt_does_not_overwrite_first(self):
        self.intent()
        self.receipt()
        conflict = self.receipt(
            external_receipt_id="different-op",
            outcome={"commit": "different", "result": "applied"},
        )
        self.assertEqual(conflict, {"status": "REJECT", "reason": "receipt_conflict"})
        self.assertEqual(
            self.store.inspect(self.args["idempotency_key"])["external_receipt_id"],
            "remote-op-77",
        )

    def test_ER09_uncertain_outcome_requires_reconciliation(self):
        self.intent()
        self.assertTrue(self.store.mark_reconciliation_required(self.args["idempotency_key"]))
        self.assertEqual(
            self.store.inspect(self.args["idempotency_key"])["status"],
            "RECONCILIATION_REQUIRED",
        )

    def test_ER10_receipt_can_resolve_uncertain_outcome(self):
        self.intent()
        self.store.mark_reconciliation_required(self.args["idempotency_key"])
        result = self.receipt()
        self.assertEqual(result["status"], "RECEIVED")

    def test_ER11_inspection_is_read_only_and_absence_is_none(self):
        self.intent()
        before = self.store.inspect(self.args["idempotency_key"])
        self.assertIsNone(self.store.inspect("missing"))
        self.assertEqual(self.store.inspect(self.args["idempotency_key"]), before)

    def test_ER12_receipt_outcome_digest_is_stable_for_mapping_order(self):
        self.intent()
        first = self.receipt(outcome={"a": 1, "b": 2})
        self.assertEqual(first["status"], "RECEIVED")
        retry = self.receipt(outcome={"b": 2, "a": 1})
        self.assertEqual(retry["status"], "REPLAY")


if __name__ == "__main__":
    unittest.main()
