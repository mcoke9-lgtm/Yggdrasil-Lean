import tempfile
import unittest
from pathlib import Path

from adaptive_filesystem_runtime.attempt_ledger import AttemptRecord
from adaptive_filesystem_runtime.sqlite_ledger import SQLiteAttemptLedger


def record(**overrides):
    values = dict(
        project_id="project-1", operation_id="operation-1",
        attempt_id="attempt-1", idempotency_key="idem-1", attempt_number=1,
        operation_fingerprint="input-v1", branch_id="branch-a",
        checkpoint_id="AF-PILOT-006", result_status="blocked",
        evidence_reference="test://evidence/1",
    )
    values.update(overrides)
    return AttemptRecord(**values)


class SQLiteAttemptLedgerTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.path = Path(self.tmp.name) / "attempts.sqlite3"
        self.ledger = SQLiteAttemptLedger(self.path)

    def tearDown(self):
        self.tmp.cleanup()

    def test_attempt_survives_close_and_reopen(self):
        first = record()
        self.assertEqual(self.ledger.append(first).outcome, "APPEND")
        reopened = SQLiteAttemptLedger(self.path)
        self.assertEqual(reopened.list_attempts("project-1", "operation-1"), (first,))

    def test_exact_retry_of_append_is_idempotent_after_reopen(self):
        first = record()
        self.ledger.append(first)
        reopened = SQLiteAttemptLedger(self.path)
        self.assertEqual(reopened.append(first).outcome, "IDEMPOTENT_REPLAY")

    def test_same_operation_attempt_number_conflicts_across_branch_change(self):
        self.ledger.append(record())
        reopened = SQLiteAttemptLedger(self.path)
        duplicate = record(
            attempt_id="attempt-other", idempotency_key="idem-other",
            branch_id="new-chat", checkpoint_id="AF-PILOT-007",
        )
        self.assertEqual(reopened.append(duplicate).outcome, "REJECT_CONFLICT")

    def test_sequential_retry_persists_across_reopen(self):
        self.ledger.append(record())
        reopened = SQLiteAttemptLedger(self.path)
        retry = record(
            attempt_id="attempt-2", idempotency_key="idem-2", attempt_number=2,
            operation_fingerprint="input-v2", branch_id="branch-b",
            checkpoint_id="AF-PILOT-007",
        )
        self.assertEqual(reopened.append(retry).outcome, "APPEND")
        self.assertEqual(
            [r.attempt_number for r in SQLiteAttemptLedger(self.path).list_attempts("project-1", "operation-1")],
            [1, 2],
        )

    def test_reused_key_with_changed_content_is_rejected(self):
        self.ledger.append(record())
        self.assertEqual(
            SQLiteAttemptLedger(self.path).append(record(result_status="done")).outcome,
            "REJECT_CONFLICT",
        )

    def test_different_operation_can_start_at_one(self):
        self.ledger.append(record())
        other = record(
            project_id="project-1", operation_id="operation-2",
            attempt_id="attempt-new", idempotency_key="idem-new",
        )
        self.assertEqual(self.ledger.append(other).outcome, "APPEND")


if __name__ == "__main__":
    unittest.main()
