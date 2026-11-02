"""Durable handoff ledger for bounded Adaptive Filesystem branch recovery.

Notifications are deliberately outside this module: they are signals to inspect
ledger state and never advance acknowledgement state by themselves.
"""
from __future__ import annotations

from dataclasses import asdict, dataclass
from datetime import datetime, timezone
import json
from pathlib import Path
import sqlite3
from typing import Literal

HandoffState = Literal["SENT", "RECEIVED", "RECONCILED", "BLOCKED"]


@dataclass(frozen=True)
class Handoff:
    message_id: str
    recovery_id: str
    sender: str
    recipient: str
    checkpoint_version: str
    requested_action: str
    report: str
    source_refs: tuple[str, ...]
    dependencies: tuple[str, ...]
    next_action: str
    state: HandoffState
    created_at: str
    updated_at: str


@dataclass(frozen=True)
class HandoffWrite:
    outcome: Literal["APPEND", "IDEMPOTENT_REPLAY", "REJECT_CONFLICT"]
    handoff: Handoff | None
    reason: str


class HandoffLedger:
    """SQLite-backed immutable handoff identity with guarded state transitions."""

    def __init__(self, path: str | Path):
        self.path = str(path)
        if self.path != ":memory:":
            Path(self.path).parent.mkdir(parents=True, exist_ok=True)
        with self._connect() as db:
            db.execute("""
                CREATE TABLE IF NOT EXISTS handoffs (
                    message_id TEXT PRIMARY KEY,
                    recovery_id TEXT NOT NULL,
                    sender TEXT NOT NULL,
                    recipient TEXT NOT NULL,
                    checkpoint_version TEXT NOT NULL,
                    requested_action TEXT NOT NULL,
                    report TEXT NOT NULL,
                    source_refs TEXT NOT NULL,
                    dependencies TEXT NOT NULL,
                    next_action TEXT NOT NULL,
                    state TEXT NOT NULL CHECK (
                        state IN ('SENT', 'RECEIVED', 'RECONCILED', 'BLOCKED')
                    ),
                    created_at TEXT NOT NULL,
                    updated_at TEXT NOT NULL
                )
            """)
            db.execute("""
                CREATE INDEX IF NOT EXISTS handoffs_recovery_id
                ON handoffs(recovery_id, created_at)
            """)

    def _connect(self) -> sqlite3.Connection:
        db = sqlite3.connect(self.path, timeout=10, isolation_level=None)
        db.row_factory = sqlite3.Row
        db.execute("PRAGMA foreign_keys = ON")
        return db

    @staticmethod
    def _now() -> str:
        return datetime.now(timezone.utc).isoformat()

    @staticmethod
    def _text(value: object, name: str) -> str:
        if not isinstance(value, str) or not value.strip():
            raise ValueError(f"{name} must be non-empty text")
        return value.strip()

    @staticmethod
    def _refs(values: object, name: str) -> tuple[str, ...]:
        if not isinstance(values, (tuple, list)):
            raise ValueError(f"{name} must be a list or tuple of non-empty text")
        result = []
        for value in values:
            if not isinstance(value, str) or not value.strip():
                raise ValueError(f"{name} entries must be non-empty text")
            result.append(value.strip())
        return tuple(result)

    @staticmethod
    def _from_row(row: sqlite3.Row) -> Handoff:
        return Handoff(
            message_id=row["message_id"],
            recovery_id=row["recovery_id"],
            sender=row["sender"],
            recipient=row["recipient"],
            checkpoint_version=row["checkpoint_version"],
            requested_action=row["requested_action"],
            report=row["report"],
            source_refs=tuple(json.loads(row["source_refs"])),
            dependencies=tuple(json.loads(row["dependencies"])),
            next_action=row["next_action"],
            state=row["state"],
            created_at=row["created_at"],
            updated_at=row["updated_at"],
        )

    def get(self, message_id: str) -> Handoff | None:
        message_id = self._text(message_id, "message_id")
        with self._connect() as db:
            row = db.execute(
                "SELECT * FROM handoffs WHERE message_id = ?", (message_id,)
            ).fetchone()
        return self._from_row(row) if row else None

    def list_recovery(self, recovery_id: str) -> tuple[Handoff, ...]:
        recovery_id = self._text(recovery_id, "recovery_id")
        with self._connect() as db:
            rows = db.execute(
                "SELECT * FROM handoffs WHERE recovery_id = ? ORDER BY created_at, message_id",
                (recovery_id,),
            ).fetchall()
        return tuple(self._from_row(row) for row in rows)

    def record(
        self, *, message_id: str, recovery_id: str, sender: str, recipient: str,
        checkpoint_version: str, current_checkpoint_version: str,
        requested_action: str, report: str, source_refs: tuple[str, ...] | list[str] = (),
        dependencies: tuple[str, ...] | list[str] = (), next_action: str,
    ) -> HandoffWrite:
        """Append a handoff; stale checkpoints are durably recorded as BLOCKED.

        Reusing a message ID with identical content is idempotent. Reusing it with
        changed content is rejected. A stale checkpoint cannot be marked SENT.
        """
        fields = {
            "message_id": self._text(message_id, "message_id"),
            "recovery_id": self._text(recovery_id, "recovery_id"),
            "sender": self._text(sender, "sender"),
            "recipient": self._text(recipient, "recipient"),
            "checkpoint_version": self._text(checkpoint_version, "checkpoint_version"),
            "requested_action": self._text(requested_action, "requested_action"),
            "report": self._text(report, "report"),
            "source_refs": self._refs(source_refs, "source_refs"),
            "dependencies": self._refs(dependencies, "dependencies"),
            "next_action": self._text(next_action, "next_action"),
        }
        current = self._text(current_checkpoint_version, "current_checkpoint_version")
        payload = (
            fields["recovery_id"], fields["sender"], fields["recipient"],
            fields["checkpoint_version"], fields["requested_action"], fields["report"],
            json.dumps(fields["source_refs"], separators=(",", ":")),
            json.dumps(fields["dependencies"], separators=(",", ":")),
            fields["next_action"],
        )
        now = self._now()
        initial_state: HandoffState = (
            "SENT" if fields["checkpoint_version"] == current else "BLOCKED"
        )
        with self._connect() as db:
            db.execute("BEGIN IMMEDIATE")
            existing = db.execute(
                "SELECT * FROM handoffs WHERE message_id = ?",
                (fields["message_id"],),
            ).fetchone()
            if existing:
                old_payload = (
                    existing["recovery_id"], existing["sender"], existing["recipient"],
                    existing["checkpoint_version"], existing["requested_action"],
                    existing["report"], existing["source_refs"], existing["dependencies"],
                    existing["next_action"],
                )
                db.commit()
                if old_payload == payload:
                    return HandoffWrite(
                        "IDEMPOTENT_REPLAY", self._from_row(existing),
                        "message ID already exists with identical content",
                    )
                return HandoffWrite(
                    "REJECT_CONFLICT", self._from_row(existing),
                    "message ID was reused with different content",
                )
            db.execute("""
                INSERT INTO handoffs (
                    message_id, recovery_id, sender, recipient, checkpoint_version,
                    requested_action, report, source_refs, dependencies, next_action,
                    state, created_at, updated_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """, (
                fields["message_id"], *payload[:6], payload[6], payload[7],
                payload[8], initial_state, now, now,
            ))
            row = db.execute(
                "SELECT * FROM handoffs WHERE message_id = ?",
                (fields["message_id"],),
            ).fetchone()
            db.commit()
        return HandoffWrite(
            "APPEND", self._from_row(row),
            "checkpoint matched; handoff recorded" if initial_state == "SENT"
            else "checkpoint mismatch; handoff recorded BLOCKED",
        )

    def acknowledge(
        self, message_id: str, *, state: Literal["RECEIVED", "RECONCILED"],
        actor: str, checkpoint_version: str,
    ) -> HandoffWrite:
        """Advance state only with explicit actor and matching checkpoint version."""
        message_id = self._text(message_id, "message_id")
        actor = self._text(actor, "actor")
        checkpoint_version = self._text(checkpoint_version, "checkpoint_version")
        if state not in ("RECEIVED", "RECONCILED"):
            raise ValueError("acknowledgement state must be RECEIVED or RECONCILED")
        now = self._now()
        with self._connect() as db:
            db.execute("BEGIN IMMEDIATE")
            row = db.execute(
                "SELECT * FROM handoffs WHERE message_id = ?", (message_id,)
            ).fetchone()
            if row is None:
                db.commit()
                return HandoffWrite("REJECT_CONFLICT", None, "unknown message ID")
            current = self._from_row(row)
            if current.state == "BLOCKED":
                db.commit()
                return HandoffWrite("REJECT_CONFLICT", current, "blocked handoff cannot be acknowledged")
            if current.checkpoint_version != checkpoint_version:
                db.commit()
                return HandoffWrite("REJECT_CONFLICT", current, "checkpoint version mismatch")
            if state == "RECEIVED" and current.state != "SENT":
                db.commit()
                if current.state in ("RECEIVED", "RECONCILED"):
                    return HandoffWrite("IDEMPOTENT_REPLAY", current, "receipt already recorded")
                return HandoffWrite("REJECT_CONFLICT", current, "invalid receipt transition")
            if state == "RECONCILED" and current.state not in ("RECEIVED", "RECONCILED"):
                db.commit()
                return HandoffWrite("REJECT_CONFLICT", current, "reconciliation requires RECEIVED state")
            if current.state == state:
                db.commit()
                return HandoffWrite("IDEMPOTENT_REPLAY", current, "state already recorded")
            # actor is required as evidence of an explicit acknowledgement; it is
            # represented in the append-only audit table, not used as identity.
            db.execute("""
                CREATE TABLE IF NOT EXISTS handoff_events (
                    event_id INTEGER PRIMARY KEY AUTOINCREMENT,
                    message_id TEXT NOT NULL,
                    actor TEXT NOT NULL,
                    prior_state TEXT NOT NULL,
                    new_state TEXT NOT NULL,
                    checkpoint_version TEXT NOT NULL,
                    created_at TEXT NOT NULL,
                    FOREIGN KEY(message_id) REFERENCES handoffs(message_id)
                )
            """)
            db.execute("""
                INSERT INTO handoff_events (
                    message_id, actor, prior_state, new_state, checkpoint_version, created_at
                ) VALUES (?, ?, ?, ?, ?, ?)
            """, (message_id, actor, current.state, state, checkpoint_version, now))
            db.execute(
                "UPDATE handoffs SET state = ?, updated_at = ? WHERE message_id = ?",
                (state, now, message_id),
            )
            updated = db.execute(
                "SELECT * FROM handoffs WHERE message_id = ?", (message_id,)
            ).fetchone()
            db.commit()
        return HandoffWrite("APPEND", self._from_row(updated), "explicit acknowledgement recorded")
