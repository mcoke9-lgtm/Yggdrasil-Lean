"""SQLite-backed execution-ledger prototype contract tests.

This is a local transactional prototype, not a deployed cross-platform service.
SQLite provides atomic transactions for this single database; network partitions,
multi-database coordination, production durability policy, and cryptographic
authorization verification remain outside this module.

Run:
    python -m unittest discover -s tests/adaptive_filesystem -p 'test_*.py' -v
"""
import sqlite3
import tempfile
import threading
import unittest
from pathlib import Path


class SQLiteExecutionLedger:
    """Single-database, single-use authorization ledger with idempotent replay."""

    def __init__(self, database_path):
        self.database_path = str(database_path)
        with self._connect() as db:
            # Set journal mode once during initialization, not on every connection.
            db.execute("PRAGMA journal_mode=WAL")
            db.execute("""
                CREATE TABLE IF NOT EXISTS executions (
                    authorization_id TEXT PRIMARY KEY,
                    action_fingerprint TEXT NOT NULL,
                    status TEXT NOT NULL CHECK(status IN ('authorized', 'revoked', 'committed')),
                    outcome TEXT
                )
            """)

    def _connect(self):
        db = sqlite3.connect(self.database_path, timeout=10, isolation_level=None)
        db.execute("PRAGMA synchronous=FULL")
        return db

    def register(self, authorization_id, action_fingerprint):
        with self._connect() as db:
            db.execute(
                "INSERT INTO executions VALUES (?, ?, 'authorized', NULL)",
                (authorization_id, action_fingerprint),
            )

    def revoke(self, authorization_id):
        db = self._connect()
        try:
            db.execute("BEGIN IMMEDIATE")
            cursor = db.execute(
                """UPDATE executions SET status='revoked'
                   WHERE authorization_id=? AND status='authorized'""",
                (authorization_id,),
            )
            db.execute("COMMIT")
            return cursor.rowcount == 1
        except Exception:
            if db.in_transaction:
                db.execute("ROLLBACK")
            raise
        finally:
            db.close()

    def commit_once(self, authorization_id, action_fingerprint, outcome,
                    *, interrupt_before_commit=False):
        """Atomically consume one authorization and store its outcome.

        Repeating the same request returns the committed outcome. Reusing the
        authorization for a different action is rejected.
        """
        db = self._connect()
        try:
            db.execute("BEGIN IMMEDIATE")
            row = db.execute(
                """SELECT action_fingerprint, status, outcome
                   FROM executions WHERE authorization_id=?""",
                (authorization_id,),
            ).fetchone()
            if row is None:
                db.execute("ROLLBACK")
                return {"status": "REJECT", "reason": "authorization_unknown"}
            stored_fingerprint, status, stored_outcome = row
            if stored_fingerprint != action_fingerprint:
                db.execute("ROLLBACK")
                return {"status": "REJECT", "reason": "action_fingerprint_mismatch"}
            if status == "committed":
                db.execute("COMMIT")
                return {"status": "REPLAY", "outcome": stored_outcome}
            if status == "revoked":
                db.execute("ROLLBACK")
                return {"status": "REJECT", "reason": "authorization_revoked"}
            if interrupt_before_commit:
                db.execute("ROLLBACK")
                return {"status": "INTERRUPTED", "reason": "before_commit"}
            db.execute(
                """UPDATE executions SET status='committed', outcome=?
                   WHERE authorization_id=? AND status='authorized'""",
                (outcome, authorization_id),
            )
            db.execute("COMMIT")
            return {"status": "COMMITTED", "outcome": outcome}
        except Exception:
            if db.in_transaction:
                db.execute("ROLLBACK")
            raise
        finally:
            db.close()


class ExecutionLedgerContractTests(unittest.TestCase):
    def setUp(self):
        self.tempdir = tempfile.TemporaryDirectory()
        self.db_path = Path(self.tempdir.name) / "ledger.sqlite"
        self.ledger = SQLiteExecutionLedger(self.db_path)
        self.ledger.register("auth-one", "advance:Roots->Trunk")

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

    def test_EL03_interruption_before_commit_leaves_authorization_available(self):
        interrupted = self.ledger.commit_once(
            "auth-one", "advance:Roots->Trunk", "checkpoint-8",
            interrupt_before_commit=True,
        )
        self.assertEqual(interrupted["status"], "INTERRUPTED")
        resumed = self.ledger.commit_once(
            "auth-one", "advance:Roots->Trunk", "checkpoint-8"
        )
        self.assertEqual(resumed["status"], "COMMITTED")

    def test_EL04_committed_outcome_survives_new_ledger_instance(self):
        self.ledger.commit_once(
            "auth-one", "advance:Roots->Trunk", "checkpoint-8"
        )
        reopened = SQLiteExecutionLedger(self.db_path)
        replay = reopened.commit_once(
            "auth-one", "advance:Roots->Trunk", "ignored"
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

    def test_EL06_same_authorization_cannot_be_reused_for_another_action(self):
        self.ledger.commit_once(
            "auth-one", "advance:Roots->Trunk", "checkpoint-8"
        )
        result = self.ledger.commit_once(
            "auth-one", "delete:record-7", "deleted"
        )
        self.assertEqual(result, {
            "status": "REJECT", "reason": "action_fingerprint_mismatch"
        })

    def test_EL07_unknown_authorization_fails_closed(self):
        result = self.ledger.commit_once(
            "auth-missing", "advance:Roots->Trunk", "checkpoint-8"
        )
        self.assertEqual(result, {
            "status": "REJECT", "reason": "authorization_unknown"
        })


if __name__ == "__main__":
    unittest.main()
