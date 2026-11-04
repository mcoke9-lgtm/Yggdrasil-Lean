"""Contract tests for the reusable SQLite execution-ledger prototype.

Run:
    python -m unittest discover -s tests/adaptive_filesystem -p 'test_*.py' -v
"""
import os
import subprocess
import sys
import tempfile
import threading
import unittest
from pathlib import Path

from adaptive_filesystem.execution_ledger import SQLiteExecutionLedger


class ExecutionLedgerContractTests(unittest.TestCase):
    def setUp(self):
        self.tempdir = tempfile.TemporaryDirectory()
        self.db_path = Path(self.tempdir.name) / "ledger.sqlite"
        self.ledger = SQLiteExecutionLedger(self.db_path)
        self.ledger.register_verified_authorization(
            "auth-one", "advance:Roots->Trunk"
        )

    def tearDown(self):
        self.tempdir.cleanup()

    def test_EL01_two_simultaneous_claims_cannot_both_commit(self):
        barrier = threading.Barrier(2)
        results = []
        errors = []
        result_lock = threading.Lock()

        def contender(outcome):
            try:
                barrier.wait()
                result = self.ledger.commit_once(
                    "auth-one", "advance:Roots->Trunk", outcome
                )
                with result_lock:
                    results.append(result)
            except Exception as exc:
                with result_lock:
                    errors.append(exc)

        threads = [
            threading.Thread(target=contender, args=("result-A",)),
            threading.Thread(target=contender, args=("result-B",)),
        ]
        for thread in threads:
            thread.start()
        for thread in threads:
            thread.join()

        self.assertEqual(errors, [])
        committed = [r for r in results if r["status"] == "COMMITTED"]
        replayed = [r for r in results if r["status"] == "REPLAY"]
        self.assertEqual(len(committed), 1)
        self.assertEqual(len(replayed), 1)
        self.assertEqual(replayed[0]["outcome"], committed[0]["outcome"])

    def test_EL02_retry_after_lost_acknowledgement_returns_original_outcome(self):
        first = self.ledger.commit_once(
            "auth-one", "advance:Roots->Trunk", "checkpoint-8"
        )
        retry = self.ledger.commit_once(
            "auth-one", "advance:Roots->Trunk", "different-retry-payload"
        )
        self.assertEqual(first, {"status": "COMMITTED", "outcome": "checkpoint-8"})
        self.assertEqual(retry, {"status": "REPLAY", "outcome": "checkpoint-8"})

    def test_EL03_process_exit_before_transaction_commit_rolls_back(self):
        script = """
import os, sqlite3, sys
db = sqlite3.connect(sys.argv[1], isolation_level=None)
db.execute("BEGIN IMMEDIATE")
db.execute("UPDATE executions SET status='committed', outcome='unacknowledged' WHERE authorization_id='auth-one'")
os._exit(73)
"""
        result = subprocess.run([sys.executable, "-c", script, str(self.db_path)])
        self.assertEqual(result.returncode, 73)
        state = self.ledger.inspect("auth-one")
        self.assertEqual(state["status"], "authorized")
        self.assertIsNone(state["outcome"])
        resumed = self.ledger.commit_once(
            "auth-one", "advance:Roots->Trunk", "checkpoint-8"
        )
        self.assertEqual(resumed["status"], "COMMITTED")

    def test_EL04_process_exit_after_commit_before_ack_is_recoverable(self):
        script = """
import os, sys
from adaptive_filesystem.execution_ledger import SQLiteExecutionLedger
ledger = SQLiteExecutionLedger(sys.argv[1])
result = ledger.commit_once('auth-one', 'advance:Roots->Trunk', 'checkpoint-8')
assert result['status'] == 'COMMITTED'
os._exit(74)
"""
        result = subprocess.run(
            [sys.executable, "-c", script, str(self.db_path)],
            cwd=Path(__file__).resolve().parents[2],
        )
        self.assertEqual(result.returncode, 74)
        reopened = SQLiteExecutionLedger(self.db_path)
        replay = reopened.commit_once(
            "auth-one", "advance:Roots->Trunk", "ignored-retry-payload"
        )
        self.assertEqual(replay, {"status": "REPLAY", "outcome": "checkpoint-8"})

    def test_EL05_revocation_before_commit_blocks_execution(self):
        self.assertTrue(self.ledger.revoke("auth-one"))
        result = self.ledger.commit_once(
            "auth-one", "advance:Roots->Trunk", "checkpoint-8"
        )
        self.assertEqual(result, {
            "status": "REJECT", "reason": "authorization_revoked"
        })

    def test_EL06_revocation_after_commit_cannot_rewrite_history(self):
        self.ledger.commit_once(
            "auth-one", "advance:Roots->Trunk", "checkpoint-8"
        )
        self.assertFalse(self.ledger.revoke("auth-one"))
        self.assertEqual(self.ledger.inspect("auth-one")["status"], "committed")

    def test_EL07_concurrent_revocation_and_commit_resolve_to_one_serialized_state(self):
        barrier = threading.Barrier(2)
        results = {}
        errors = []

        def commit():
            try:
                barrier.wait()
                results["commit"] = self.ledger.commit_once(
                    "auth-one", "advance:Roots->Trunk", "checkpoint-8"
                )
            except Exception as exc:
                errors.append(exc)

        def revoke():
            try:
                barrier.wait()
                results["revoke"] = self.ledger.revoke("auth-one")
            except Exception as exc:
                errors.append(exc)

        threads = [threading.Thread(target=commit), threading.Thread(target=revoke)]
        for thread in threads:
            thread.start()
        for thread in threads:
            thread.join()

        self.assertEqual(errors, [])
        state = self.ledger.inspect("auth-one")
        if state["status"] == "committed":
            self.assertEqual(results["commit"]["status"], "COMMITTED")
            self.assertFalse(results["revoke"])
        else:
            self.assertEqual(state["status"], "revoked")
            self.assertEqual(results["commit"]["reason"], "authorization_revoked")
            self.assertTrue(results["revoke"])

    def test_EL08_same_authorization_cannot_be_reused_for_another_action(self):
        self.ledger.commit_once(
            "auth-one", "advance:Roots->Trunk", "checkpoint-8"
        )
        result = self.ledger.commit_once(
            "auth-one", "delete:record-7", "deleted"
        )
        self.assertEqual(result, {
            "status": "REJECT", "reason": "action_fingerprint_mismatch"
        })

    def test_EL09_unknown_authorization_fails_closed(self):
        result = self.ledger.commit_once(
            "auth-missing", "advance:Roots->Trunk", "checkpoint-8"
        )
        self.assertEqual(result, {
            "status": "REJECT", "reason": "authorization_unknown"
        })

    def test_EL10_inspection_is_read_only_and_unknown_is_none(self):
        before = self.ledger.inspect("auth-one")
        self.assertEqual(before["status"], "authorized")
        self.assertIsNone(self.ledger.inspect("auth-missing"))
        self.assertEqual(self.ledger.inspect("auth-one"), before)


if __name__ == "__main__":
    unittest.main()
