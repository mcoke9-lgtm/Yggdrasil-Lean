"""SQLite-backed experimental attempt ledger.

This is a local persistence prototype, not a production/Airtable adapter.
Writes use a transaction and database uniqueness constraints to serialize
conflicting appends. Callers must still supply a stable operation_id.
"""
from __future__ import annotations

import json
import sqlite3
from dataclasses import asdict
from pathlib import Path

from .attempt_ledger import AttemptRecord, AppendDecision, decide_append


class SQLiteAttemptLedger:
    def __init__(self, path: str | Path):
        self.path = str(path)
        with self._connect() as db:
            db.execute("""
                CREATE TABLE IF NOT EXISTS attempts (
                    project_id TEXT NOT NULL,
                    operation_id TEXT NOT NULL,
                    attempt_id TEXT NOT NULL UNIQUE,
                    idempotency_key TEXT NOT NULL UNIQUE,
                    attempt_number INTEGER NOT NULL CHECK (attempt_number >= 1),
                    operation_fingerprint TEXT NOT NULL,
                    branch_id TEXT NOT NULL,
                    checkpoint_id TEXT NOT NULL,
                    result_status TEXT NOT NULL,
                    evidence_reference TEXT,
                    payload_json TEXT NOT NULL,
                    UNIQUE(project_id, operation_id, attempt_number)
                )
            """)

    def _connect(self):
        db = sqlite3.connect(self.path, timeout=10, isolation_level=None)
        db.row_factory = sqlite3.Row
        return db

    @staticmethod
    def _record(row: sqlite3.Row) -> AttemptRecord:
        return AttemptRecord(
            project_id=row["project_id"], operation_id=row["operation_id"],
            attempt_id=row["attempt_id"], idempotency_key=row["idempotency_key"],
            attempt_number=row["attempt_number"],
            operation_fingerprint=row["operation_fingerprint"],
            branch_id=row["branch_id"], checkpoint_id=row["checkpoint_id"],
            result_status=row["result_status"], evidence_reference=row["evidence_reference"],
        )

    def list_attempts(self, project_id: str, operation_id: str) -> tuple[AttemptRecord, ...]:
        with self._connect() as db:
            rows = db.execute(
                "SELECT * FROM attempts WHERE project_id=? AND operation_id=? ORDER BY attempt_number",
                (project_id, operation_id),
            ).fetchall()
        return tuple(self._record(row) for row in rows)

    def append(self, proposed: AttemptRecord) -> AppendDecision:
        db = self._connect()
        try:
            db.execute("BEGIN IMMEDIATE")
            rows = db.execute("SELECT * FROM attempts ORDER BY rowid").fetchall()
            existing = tuple(self._record(row) for row in rows)
            decision = decide_append(existing, proposed)
            if decision.outcome == "IDEMPOTENT_REPLAY":
                db.execute("COMMIT")
                return decision
            if decision.outcome != "APPEND":
                db.execute("ROLLBACK")
                return decision
            payload = json.dumps(asdict(proposed), sort_keys=True, separators=(",", ":"))
            db.execute("""
                INSERT INTO attempts (
                    project_id, operation_id, attempt_id, idempotency_key,
                    attempt_number, operation_fingerprint, branch_id, checkpoint_id,
                    result_status, evidence_reference, payload_json
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """, (
                proposed.project_id, proposed.operation_id, proposed.attempt_id,
                proposed.idempotency_key, proposed.attempt_number,
                proposed.operation_fingerprint, proposed.branch_id, proposed.checkpoint_id,
                proposed.result_status, proposed.evidence_reference, payload,
            ))
            db.execute("COMMIT")
            return decision
        except sqlite3.IntegrityError:
            db.execute("ROLLBACK")
            return AppendDecision("REJECT_CONFLICT", "database uniqueness constraint rejected append")
        finally:
            db.close()
