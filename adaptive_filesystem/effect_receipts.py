"""Durable external-effect receipt protocol for the AF prototype.

This store records intent and evidence returned by an external executor. It
cannot make an external side effect atomic, prove the external system honest,
or independently discover remote state. Idempotency must be enforced by the
external system or reconciled through its authoritative read API.
"""
import hashlib
import json
import sqlite3


class SQLiteEffectReceiptStore:
    """Persist one idempotent effect intent and its receipt/reconciliation state."""

    def __init__(self, database_path):
        self.database_path = str(database_path)
        with self._connect() as db:
            db.execute("PRAGMA journal_mode=WAL")
            db.execute("""
                CREATE TABLE IF NOT EXISTS effect_records (
                    idempotency_key TEXT PRIMARY KEY,
                    authorization_id TEXT NOT NULL,
                    action_fingerprint TEXT NOT NULL,
                    environment_id TEXT NOT NULL,
                    environment_action TEXT NOT NULL,
                    status TEXT NOT NULL CHECK(status IN (
                        'INTENDED', 'RECEIVED', 'RECONCILIATION_REQUIRED'
                    )),
                    external_receipt_id TEXT,
                    outcome_digest TEXT
                )
            """)

    def _connect(self):
        db = sqlite3.connect(self.database_path, timeout=10, isolation_level=None)
        db.execute("PRAGMA synchronous=FULL")
        return db

    @staticmethod
    def _required_text(value, field):
        if not isinstance(value, str) or not value.strip():
            raise ValueError(field + " must be a non-empty string")
        return value

    @staticmethod
    def _digest_outcome(outcome):
        encoded = json.dumps(
            outcome, sort_keys=True, separators=(",", ":"),
            ensure_ascii=False, allow_nan=False,
        ).encode("utf-8")
        return "sha256:" + hashlib.sha256(encoded).hexdigest()

    def register_intent(
        self, idempotency_key, authorization_id, action_fingerprint,
        environment_id, environment_action,
    ):
        """Persist intent before calling the external executor; exact retry only."""
        values = (
            self._required_text(idempotency_key, "idempotency_key"),
            self._required_text(authorization_id, "authorization_id"),
            self._required_text(action_fingerprint, "action_fingerprint"),
            self._required_text(environment_id, "environment_id"),
            self._required_text(environment_action, "environment_action"),
        )
        db = self._connect()
        try:
            db.execute("BEGIN IMMEDIATE")
            row = db.execute(
                """SELECT authorization_id, action_fingerprint, environment_id,
                          environment_action, status
                   FROM effect_records WHERE idempotency_key=?""",
                (values[0],),
            ).fetchone()
            if row:
                if row[:4] != values[1:]:
                    db.execute("ROLLBACK")
                    return {"status": "REJECT", "reason": "idempotency_key_conflict"}
                db.execute("COMMIT")
                return {"status": "REPLAY", "state": row[4]}
            db.execute(
                """INSERT INTO effect_records
                   (idempotency_key, authorization_id, action_fingerprint,
                    environment_id, environment_action, status)
                   VALUES (?, ?, ?, ?, ?, 'INTENDED')""",
                values,
            )
            db.execute("COMMIT")
            return {"status": "INTENDED", "idempotency_key": values[0]}
        except Exception:
            if db.in_transaction:
                db.execute("ROLLBACK")
            raise
        finally:
            db.close()

    def record_receipt(
        self, idempotency_key, authorization_id, action_fingerprint,
        environment_id, environment_action, external_receipt_id, outcome,
    ):
        """Store a matching external receipt; identical retry returns REPLAY."""
        receipt_id = self._required_text(external_receipt_id, "external_receipt_id")
        digest = self._digest_outcome(outcome)
        db = self._connect()
        try:
            db.execute("BEGIN IMMEDIATE")
            row = db.execute(
                """SELECT authorization_id, action_fingerprint, environment_id,
                          environment_action, status, external_receipt_id, outcome_digest
                   FROM effect_records WHERE idempotency_key=?""",
                (idempotency_key,),
            ).fetchone()
            if row is None:
                db.execute("ROLLBACK")
                return {"status": "REJECT", "reason": "intent_unknown"}
            expected = (
                authorization_id, action_fingerprint, environment_id, environment_action
            )
            if row[:4] != expected:
                db.execute("ROLLBACK")
                return {"status": "REJECT", "reason": "receipt_binding_mismatch"}
            if row[4] == "RECEIVED":
                if row[5] == receipt_id and row[6] == digest:
                    db.execute("COMMIT")
                    return {"status": "REPLAY", "external_receipt_id": receipt_id}
                db.execute("ROLLBACK")
                return {"status": "REJECT", "reason": "receipt_conflict"}
            db.execute(
                """UPDATE effect_records SET status='RECEIVED',
                   external_receipt_id=?, outcome_digest=?
                   WHERE idempotency_key=? AND status IN
                     ('INTENDED', 'RECONCILIATION_REQUIRED')""",
                (receipt_id, digest, idempotency_key),
            )
            db.execute("COMMIT")
            return {
                "status": "RECEIVED",
                "idempotency_key": idempotency_key,
                "external_receipt_id": receipt_id,
                "outcome_digest": digest,
            }
        except Exception:
            if db.in_transaction:
                db.execute("ROLLBACK")
            raise
        finally:
            db.close()

    def mark_reconciliation_required(self, idempotency_key):
        """Mark an uncertain external outcome; never assert success or failure."""
        with self._connect() as db:
            cursor = db.execute(
                """UPDATE effect_records SET status='RECONCILIATION_REQUIRED'
                   WHERE idempotency_key=? AND status='INTENDED'""",
                (idempotency_key,),
            )
        return cursor.rowcount == 1

    def inspect(self, idempotency_key):
        """Read-only view; absence is not evidence that an effect did not happen."""
        with self._connect() as db:
            row = db.execute(
                """SELECT idempotency_key, authorization_id, action_fingerprint,
                          environment_id, environment_action, status,
                          external_receipt_id, outcome_digest
                   FROM effect_records WHERE idempotency_key=?""",
                (idempotency_key,),
            ).fetchone()
        if row is None:
            return None
        fields = (
            "idempotency_key", "authorization_id", "action_fingerprint",
            "environment_id", "environment_action", "status",
            "external_receipt_id", "outcome_digest",
        )
        return dict(zip(fields, row))
