import tempfile
import unittest
from pathlib import Path

from adaptive_filesystem_runtime.handoff_ledger import HandoffLedger


def record(**overrides):
    values = dict(
        message_id="msg-001",
        recovery_id="AF-HEALTH-001",
        sender="trunk",
        recipient="branch-review-outcome",
        checkpoint_version="checkpoint-7",
        current_checkpoint_version="checkpoint-7",
        requested_action="return_liveness_report",
        report="Branch is responsive; checkpoint preserved.",
        source_refs=("https://github.com/example/repo/commit/abc123",),
        dependencies=("backend-adapter-integration",),
        next_action="verify_checkpoint_lineage",
    )
    values.update(overrides)
    return values


class HandoffLedgerTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.path = Path(self.tmp.name) / "handoffs.sqlite3"
        self.ledger = HandoffLedger(self.path)

    def tearDown(self):
        self.tmp.cleanup()

    def test_handoff_survives_close_and_reopen(self):
        result = self.ledger.record(**record())
        self.assertEqual(result.outcome, "APPEND")
        reopened = HandoffLedger(self.path)
        stored = reopened.get("msg-001")
        self.assertIsNotNone(stored)
        self.assertEqual(stored.state, "SENT")
        self.assertEqual(stored.source_refs, record()["source_refs"])
        self.assertEqual(stored.dependencies, record()["dependencies"])

    def test_exact_duplicate_message_is_idempotent(self):
        self.assertEqual(self.ledger.record(**record()).outcome, "APPEND")
        self.assertEqual(
            HandoffLedger(self.path).record(**record()).outcome,
            "IDEMPOTENT_REPLAY",
        )
        self.assertEqual(len(self.ledger.list_recovery("AF-HEALTH-001")), 1)

    def test_reused_message_id_with_changed_payload_is_rejected(self):
        self.ledger.record(**record())
        changed = record(report="Different content under the same ID.")
        result = self.ledger.record(**changed)
        self.assertEqual(result.outcome, "REJECT_CONFLICT")
        self.assertEqual(result.handoff.report, record()["report"])

    def test_stale_checkpoint_is_persisted_blocked_and_cannot_be_acknowledged(self):
        stale = record(
            checkpoint_version="checkpoint-6",
            current_checkpoint_version="checkpoint-7",
        )
        result = self.ledger.record(**stale)
        self.assertEqual(result.outcome, "APPEND")
        self.assertEqual(result.handoff.state, "BLOCKED")
        ack = self.ledger.acknowledge(
            "msg-001", state="RECEIVED", actor="branch-review-outcome",
            checkpoint_version="checkpoint-6",
        )
        self.assertEqual(ack.outcome, "REJECT_CONFLICT")
        self.assertEqual(self.ledger.get("msg-001").state, "BLOCKED")

    def test_acknowledgement_requires_receipt_then_reconciliation(self):
        self.ledger.record(**record())
        premature = self.ledger.acknowledge(
            "msg-001", state="RECONCILED", actor="branch-review-outcome",
            checkpoint_version="checkpoint-7",
        )
        self.assertEqual(premature.outcome, "REJECT_CONFLICT")

        received = self.ledger.acknowledge(
            "msg-001", state="RECEIVED", actor="branch-review-outcome",
            checkpoint_version="checkpoint-7",
        )
        self.assertEqual(received.outcome, "APPEND")
        reconciled = self.ledger.acknowledge(
            "msg-001", state="RECONCILED", actor="branch-review-outcome",
            checkpoint_version="checkpoint-7",
        )
        self.assertEqual(reconciled.outcome, "APPEND")
        self.assertEqual(self.ledger.get("msg-001").state, "RECONCILED")

    def test_wrong_checkpoint_version_cannot_advance_state(self):
        self.ledger.record(**record())
        result = self.ledger.acknowledge(
            "msg-001", state="RECEIVED", actor="branch-review-outcome",
            checkpoint_version="checkpoint-8",
        )
        self.assertEqual(result.outcome, "REJECT_CONFLICT")
        self.assertEqual(self.ledger.get("msg-001").state, "SENT")


if __name__ == "__main__":
    unittest.main()
