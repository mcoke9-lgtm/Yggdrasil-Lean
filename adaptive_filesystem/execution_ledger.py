"""Transactional single-database execution ledger prototype.

This component records a previously verified authorization and atomically
consumes it once. It does NOT verify signatures, decide who has authority,
perform the external action, or coordinate independent databases/platforms.

The commit_once operation atomically stores the ledger outcome. Callers must
treat the returned outcome as the durable decision record and make any external
side effect idempotent/reconcilable; a database transaction cannot atomically
commit an unrelated external system.
"""
import sqlite3


class SQLiteExecutionLedger:
    """SQLite-backed single-use authorization ledger with idempotent replay."""

    def __init__(self, database_path):
        self.database_path = str(database_path)
        with self._connect() as db:
            # WAL is persistent database configuration; set it during setup.
            db.execute("PRAGMA journal_mode=WAL")
            db.execute("""
                CREATE TABLE IF NOT EXISTS executions (
                    authorization_id TEXT PRIMARY KEY,
                    action_fingerprint TEXT NOT NULL,
                    status TEXT NOT NULL
                        CHECK(status IN ('authorized', 'revoked', 'committed')),
                    outcome TEXT
                )
            """)

    def _connect(self):
        db = sqlite3.connect(self.database_path, timeout=10, isolation_level=None)
        db.execute("PRAGMA synchronous=FULL")
        return db

    def register_verified_authorization(self, authorization_id, action_fingerprint):
        """Register an authorization verified by an upstream authority layer.

        This method trusts its caller. It is not a signature or authority verifier.
        """
        if not authorization_id or not action_fingerprint:
            raise ValueError("authorization_id and action_fingerprint are required")
        with self._connect() as db:
            db.execute(
                "INSERT INTO executions VALUES (?, ?, 'authorized', NULL)",
                (authorization_id, action_fingerprint),
            )

    def revoke(self, authorization_id):
        """Revoke only an authorization not yet committed."""
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

    def commit_once(self, authorization_id, action_fingerprint, outcome):
        """Atomically consume an authorization and persist its outcome.

        Replaying a committed authorization returns the original outcome.
        An unknown, revoked, or differently-scoped authorization is rejected.
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

            cursor = db.execute(
                """UPDATE executions SET status='committed', outcome=?
                   WHERE authorization_id=? AND status='authorized'""",
                (outcome, authorization_id),
            )
            if cursor.rowcount != 1:
                db.execute("ROLLBACK")
                return {"status": "REJECT", "reason": "authorization_not_available"}
            db.execute("COMMIT")
            return {"status": "COMMITTED", "outcome": outcome}
        except Exception:
            if db.in_transaction:
                db.execute("ROLLBACK")
            raise
        finally:
            db.close()

    def inspect(self, authorization_id):
        """Return persisted state for diagnostics; never grants permission."""
        with self._connect() as db:
            row = db.execute(
                """SELECT authorization_id, action_fingerprint, status, outcome
                   FROM executions WHERE authorization_id=?""",
                (authorization_id,),
            ).fetchone()
        if row is None:
            return None
        return {
            "authorization_id": row[0],
            "action_fingerprint": row[1],
            "status": row[2],
            "outcome": row[3],
        }
