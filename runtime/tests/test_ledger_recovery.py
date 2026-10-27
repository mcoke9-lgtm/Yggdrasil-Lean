import tempfile
import unittest
from pathlib import Path
from adaptive_filesystem_runtime.attempt_ledger import AttemptRecord
from adaptive_filesystem_runtime.ledger_recovery import decide_recovery_with_ledger
from adaptive_filesystem_runtime.recovery import RecoveryFacts
from adaptive_filesystem_runtime.sqlite_ledger import SQLiteAttemptLedger

def record(**overrides):
    values = dict(project_id="project-1", operation_id="op-1", attempt_id="attempt-1",
        idempotency_key="idem-1", attempt_number=1, operation_fingerprint="v1",
        branch_id="branch-a", checkpoint_id="checkpoint-a", result_status="started",
        evidence_reference=None)
    values.update(overrides)
    return AttemptRecord(**values)

def facts(**overrides):
    values = dict(operation_id="op-1", attempt_id="attempt-1", action_class="external-write",
        durable_intent_recorded=True, external_action_started=True, outcome_evidence=None,
        idempotent_external_action=False, idempotency_key_supported_by_target=False)
    values.update(overrides)
    return RecoveryFacts(**values)

class LedgerRecoveryTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.path = Path(self.temp.name) / "attempts.sqlite3"
        self.ledger = SQLiteAttemptLedger(self.path)
        self.ledger.append(record())

    def tearDown(self):
        self.temp.cleanup()

    def test_matching_latest_attempt_still_requires_reconciliation(self):
        result = decide_recovery_with_ledger(self.ledger, "project-1", facts())
        self.assertEqual(result.ledger_status, "MATCHED_ATTEMPT")
        self.assertEqual(result.attempt_number, 1)
        self.assertEqual(result.recovery.outcome, "RECONCILE_REQUIRED")

    def test_missing_attempt_fails_closed(self):
        result = decide_recovery_with_ledger(self.ledger, "project-1", facts(attempt_id="missing"))
        self.assertEqual(result.ledger_status, "ATTEMPT_NOT_FOUND")
        self.assertEqual(result.recovery.outcome, "STOP_CONFLICT")

    def test_wrong_project_cannot_borrow_attempt(self):
        result = decide_recovery_with_ledger(self.ledger, "other-project", facts())
        self.assertEqual(result.ledger_status, "ATTEMPT_NOT_FOUND")
        self.assertEqual(result.recovery.outcome, "STOP_CONFLICT")

    def test_stale_attempt_blocked_after_newer_attempt(self):
        self.ledger.append(record(attempt_id="attempt-2", idempotency_key="idem-2",
            attempt_number=2, operation_fingerprint="v2"))
        result = decide_recovery_with_ledger(self.ledger, "project-1", facts())
        self.assertEqual(result.ledger_status, "LEDGER_CONFLICT")
        self.assertEqual(result.recovery.outcome, "STOP_CONFLICT")

    def test_restart_preserves_attempt_identity(self):
        result = decide_recovery_with_ledger(SQLiteAttemptLedger(self.path), "project-1", facts())
        self.assertEqual(result.ledger_status, "MATCHED_ATTEMPT")
        self.assertEqual(result.recovery.outcome, "RECONCILE_REQUIRED")

    def test_conflicting_recovery_facts_still_fail_closed(self):
        result = decide_recovery_with_ledger(self.ledger, "project-1",
            facts(explicit_reconciliation_result="completed"))
        self.assertEqual(result.ledger_status, "MATCHED_ATTEMPT")
        self.assertEqual(result.recovery.outcome, "STOP_CONFLICT")

if __name__ == "__main__":
    unittest.main()
