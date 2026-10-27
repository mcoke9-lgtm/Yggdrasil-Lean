"""SQLite-backed experimental attempt ledger.

This is a local persistence prototype, not a production/Airtable adapter.
Writes use transactions and database constraints. Callers must supply stable
operation identity. Recovery claims coordinate local workers; they do not
authorize or make external side effects atomic.
"""
from __future__ import annotations

import json
import sqlite3
import time
from dataclasses import asdict, dataclass
from pathlib import Path
from typing import Literal

from .attempt_ledger import AttemptRecord, AppendDecision, decide_append

ClaimStatus = Literal["CLAIMED", "ALREADY_CLAIMED", "CLAIMED_BY_OTHER", "STALE_ATTEMPT", "ATTEMPT_NOT_FOUND", "INVALID"]


@dataclass(frozen=True)
class RecoveryClaimResult:
    status: ClaimStatus
    project_id: str
    operation_id: str
    attempt_id: str
    attempt_number: int | None
    claim_id: str
    owner_id: str
    lease_expires_at: float | None
    reason: str


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
            db.execute("""
                CREATE TABLE IF NOT EXISTS recovery_claims (
                    project_id TEXT NOT NULL,
                    operation_id TEXT NOT NULL,
                    attempt_id TEXT NOT NULL,
                    attempt_number INTEGER NOT NULL,
                    claim_id TEXT NOT NULL UNIQUE,
                    owner_id TEXT NOT NULL,
                    lease_expires_at REAL NOT NULL,
                    PRIMARY KEY(project_id, operation_id)
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

    def claim_recovery_review(
        self, project_id: str, operation_id: str, attempt_id: str,
        attempt_number: int, claim_id: str, owner_id: str,
        lease_seconds: float = 30.0, now: float | None = None,
    ) -> RecoveryClaimResult:
        """Atomically claim local recovery coordination for the current attempt.

        This lease coordinates cooperating workers only; it is not permission to
        replay an external effect. External effects still require recovery policy,
        reconciliation, and target-side idempotency where applicable.
        """
        for name, value in (("project_id", project_id), ("operation_id", operation_id),
                            ("attempt_id", attempt_id), ("claim_id", claim_id), ("owner_id", owner_id)):
            if not isinstance(value, str) or not value.strip():
                return RecoveryClaimResult("INVALID", str(project_id), str(operation_id),
                    str(attempt_id), None, str(claim_id), str(owner_id), None,
                    f"{name} must be non-empty text")
        if (not isinstance(attempt_number, int) or isinstance(attempt_number, bool)
                or attempt_number < 1 or not isinstance(lease_seconds, (int, float))
                or isinstance(lease_seconds, bool) or lease_seconds <= 0):
            return RecoveryClaimResult("INVALID", project_id, operation_id, attempt_id,
                None, claim_id, owner_id, None, "attempt_number and lease_seconds must be valid")
        current_time = time.time() if now is None else now
        if not isinstance(current_time, (int, float)) or isinstance(current_time, bool):
            return RecoveryClaimResult("INVALID", project_id, operation_id, attempt_id,
                attempt_number, claim_id, owner_id, None, "now must be numeric")
        expires = float(current_time) + float(lease_seconds)
        db = self._connect()
        try:
            db.execute("BEGIN IMMEDIATE")
            latest = db.execute(
                """SELECT attempt_id, attempt_number FROM attempts
                   WHERE project_id=? AND operation_id=? ORDER BY attempt_number DESC LIMIT 1""",
                (project_id, operation_id),
            ).fetchone()
            if latest is None:
                db.execute("ROLLBACK")
                return RecoveryClaimResult("ATTEMPT_NOT_FOUND", project_id, operation_id,
                    attempt_id, None, claim_id, owner_id, None, "operation has no ledger attempts")
            if latest["attempt_id"] != attempt_id or latest["attempt_number"] != attempt_number:
                db.execute("ROLLBACK")
                return RecoveryClaimResult("STALE_ATTEMPT", project_id, operation_id,
                    attempt_id, attempt_number, claim_id, owner_id, None,
                    "claim must target the latest ledger attempt")
            existing = db.execute(
                "SELECT * FROM recovery_claims WHERE project_id=? AND operation_id=?",
                (project_id, operation_id),
            ).fetchone()
            if existing is not None and existing["lease_expires_at"] > float(current_time):
                same = (existing["claim_id"] == claim_id and existing["owner_id"] == owner_id
                        and existing["attempt_id"] == attempt_id
                        and existing["attempt_number"] == attempt_number)
                db.execute("COMMIT")
                return RecoveryClaimResult(
                    "ALREADY_CLAIMED" if same else "CLAIMED_BY_OTHER",
                    project_id, operation_id, attempt_id, attempt_number, claim_id, owner_id,
                    existing["lease_expires_at"],
                    "same claim already holds lease" if same else "another unexpired claim owns recovery coordination",
                )
            db.execute(
                """INSERT INTO recovery_claims
                   (project_id, operation_id, attempt_id, attempt_number, claim_id, owner_id, lease_expires_at)
                   VALUES (?, ?, ?, ?, ?, ?, ?)
                   ON CONFLICT(project_id, operation_id) DO UPDATE SET
                     attempt_id=excluded.attempt_id, attempt_number=excluded.attempt_number,
                     claim_id=excluded.claim_id, owner_id=excluded.owner_id,
                     lease_expires_at=excluded.lease_expires_at""",
                (project_id, operation_id, attempt_id, attempt_number, claim_id, owner_id, expires),
            )
            db.execute("COMMIT")
            return RecoveryClaimResult("CLAIMED", project_id, operation_id, attempt_id,
                attempt_number, claim_id, owner_id, expires, "local recovery coordination lease acquired")
        except sqlite3.IntegrityError:
            db.execute("ROLLBACK")
            return RecoveryClaimResult("INVALID", project_id, operation_id, attempt_id,
                attempt_number, claim_id, owner_id, None, "claim identity conflicts with an existing claim")
        finally:
            db.close()

    def release_recovery_review(
        self, project_id: str, operation_id: str, claim_id: str, owner_id: str
    ) -> bool:
        """Release only the exact claim identity; false means no matching claim."""
        with self._connect() as db:
            cursor = db.execute(
                """DELETE FROM recovery_claims
                   WHERE project_id=? AND operation_id=? AND claim_id=? AND owner_id=?""",
                (project_id, operation_id, claim_id, owner_id),
            )
            return cursor.rowcount == 1
