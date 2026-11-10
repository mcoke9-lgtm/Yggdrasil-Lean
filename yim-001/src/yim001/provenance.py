"""Append-only, hash-linked provenance events for YIM-001.

The ledger records claims about actions; it does not authenticate actors or
prove that a claimed action occurred. Hash links detect accidental or
unsophisticated alteration when compared with a trusted prior checkpoint.
"""
from __future__ import annotations

from dataclasses import dataclass
from datetime import datetime, timezone
import hashlib
import json
from typing import Any
from uuid import uuid4

from .core import ValidationError, _snapshot, _validate_json

GENESIS = "0" * 64
EVENT_TYPES = frozenset({
    "record.created",
    "record.revised",
    "record.imported",
    "record.exported",
    "record.reconciled",
    "conflict.annotated",
    "snapshot.recovered",
})


class ProvenanceError(ValueError):
    """Raised when a provenance event or ledger is invalid."""


def _canonical(value: Any) -> bytes:
    _validate_json(value, "provenance")
    try:
        return json.dumps(
            value, ensure_ascii=False, allow_nan=False,
            sort_keys=True, separators=(",", ":"),
        ).encode("utf-8")
    except (TypeError, ValueError, UnicodeError) as exc:
        raise ProvenanceError(f"cannot encode provenance data: {exc}") from exc


def _event_digest(event_without_digest: dict[str, Any]) -> str:
    return hashlib.sha256(_canonical(event_without_digest)).hexdigest()


def _timestamp(value: str | None) -> str:
    if value is None:
        return datetime.now(timezone.utc).isoformat(timespec="microseconds")
    if not isinstance(value, str) or not value:
        raise ProvenanceError("timestamp must be a non-empty ISO-8601 string")
    try:
        parsed = datetime.fromisoformat(value.replace("Z", "+00:00"))
    except ValueError as exc:
        raise ProvenanceError("timestamp must be valid ISO-8601") from exc
    if parsed.tzinfo is None or parsed.utcoffset() is None:
        raise ProvenanceError("timestamp must include a UTC offset")
    return value


@dataclass(frozen=True)
class ProvenanceEvent:
    event_id: str
    sequence: int
    event_type: str
    actor_id: str
    timestamp: str
    record_id: str | None
    version_id: str | None
    details: Any
    previous_digest: str
    digest: str

    def to_dict(self) -> dict[str, Any]:
        return {
            "event_id": self.event_id,
            "sequence": self.sequence,
            "event_type": self.event_type,
            "actor_id": self.actor_id,
            "timestamp": self.timestamp,
            "record_id": self.record_id,
            "version_id": self.version_id,
            "details": self.details,
            "previous_digest": self.previous_digest,
            "digest": self.digest,
        }


class EventLedger:
    """Append-only event sequence with a SHA-256 hash link between entries."""

    FORMAT = "YIM-001-Provenance-Ledger"
    FORMAT_VERSION = 1

    def __init__(self) -> None:
        self._events: list[ProvenanceEvent] = []

    @property
    def events(self) -> tuple[ProvenanceEvent, ...]:
        return tuple(self._events)

    def append(
        self,
        event_type: str,
        actor_id: str,
        *,
        record_id: str | None = None,
        version_id: str | None = None,
        details: Any = None,
        timestamp: str | None = None,
        event_id: str | None = None,
    ) -> ProvenanceEvent:
        if event_type not in EVENT_TYPES:
            raise ProvenanceError(f"unsupported event type: {event_type}")
        if not isinstance(actor_id, str) or not actor_id.strip():
            raise ProvenanceError("actor_id must be a non-empty string")
        for field, value in (("record_id", record_id), ("version_id", version_id)):
            if value is not None and (not isinstance(value, str) or not value.strip()):
                raise ProvenanceError(f"{field} must be null or a non-empty string")
        if not isinstance(details, (dict, list, str, int, float, bool, type(None))):
            raise ProvenanceError("details must be JSON data")
        details_copy = _snapshot({} if details is None else details, "details")
        stamp = _timestamp(timestamp)
        identifier = str(uuid4()) if event_id is None else event_id
        if not isinstance(identifier, str) or not identifier.strip():
            raise ProvenanceError("event_id must be a non-empty string")
        if any(event.event_id == identifier for event in self._events):
            raise ProvenanceError(f"duplicate event_id: {identifier}")
        previous = self._events[-1].digest if self._events else GENESIS
        row = {
            "event_id": identifier,
            "sequence": len(self._events),
            "event_type": event_type,
            "actor_id": actor_id,
            "timestamp": stamp,
            "record_id": record_id,
            "version_id": version_id,
            "details": details_copy,
            "previous_digest": previous,
        }
        digest = _event_digest(row)
        event = ProvenanceEvent(**row, digest=digest)
        self._events.append(event)
        return event

    def to_snapshot(self) -> dict[str, Any]:
        return {
            "format": self.FORMAT,
            "format_version": self.FORMAT_VERSION,
            "events": [event.to_dict() for event in self._events],
        }

    @classmethod
    def from_snapshot(cls, snapshot: Any) -> "EventLedger":
        _validate_json(snapshot, "provenance snapshot")
        if not isinstance(snapshot, dict) or set(snapshot) != {"format", "format_version", "events"}:
            raise ProvenanceError("invalid provenance snapshot structure")
        if snapshot["format"] != cls.FORMAT or type(snapshot["format_version"]) is not int or snapshot["format_version"] != cls.FORMAT_VERSION:
            raise ProvenanceError("unsupported provenance snapshot format or version")
        if not isinstance(snapshot["events"], list):
            raise ProvenanceError("provenance events must be an array")
        ledger = cls()
        seen: set[str] = set()
        previous = GENESIS
        required = {
            "event_id", "sequence", "event_type", "actor_id", "timestamp",
            "record_id", "version_id", "details", "previous_digest", "digest",
        }
        for index, row in enumerate(snapshot["events"]):
            if not isinstance(row, dict) or set(row) != required:
                raise ProvenanceError(f"invalid event structure at index {index}")
            if type(row["sequence"]) is not int or row["sequence"] != index:
                raise ProvenanceError(f"invalid event sequence at index {index}")
            if row["event_id"] in seen or not isinstance(row["event_id"], str) or not row["event_id"]:
                raise ProvenanceError(f"invalid or duplicate event identity at index {index}")
            if row["event_type"] not in EVENT_TYPES:
                raise ProvenanceError(f"unsupported event type at index {index}")
            if not isinstance(row["actor_id"], str) or not row["actor_id"].strip():
                raise ProvenanceError(f"invalid actor identity at index {index}")
            _timestamp(row["timestamp"])
            for field in ("record_id", "version_id"):
                value = row[field]
                if value is not None and (not isinstance(value, str) or not value.strip()):
                    raise ProvenanceError(f"invalid {field} at index {index}")
            _validate_json(row["details"], f"events[{index}].details")
            if row["previous_digest"] != previous:
                raise ProvenanceError(f"broken previous digest at index {index}")
            expected = _event_digest({key: value for key, value in row.items() if key != "digest"})
            if not isinstance(row["digest"], str) or row["digest"] != expected:
                raise ProvenanceError(f"event digest mismatch at index {index}")
            event = ProvenanceEvent(
                event_id=row["event_id"], sequence=row["sequence"],
                event_type=row["event_type"], actor_id=row["actor_id"],
                timestamp=row["timestamp"], record_id=row["record_id"],
                version_id=row["version_id"], details=_snapshot(row["details"], "details"),
                previous_digest=row["previous_digest"], digest=row["digest"],
            )
            ledger._events.append(event)
            seen.add(event.event_id)
            previous = event.digest
        return ledger
