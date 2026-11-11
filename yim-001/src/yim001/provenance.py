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
import re
from typing import Any
from uuid import UUID, uuid4

import rfc8785

from .core import ValidationError, _snapshot, _validate_json

GENESIS = "0" * 64
EVENT_TYPES = frozenset({
    "record.created",
    "record.create_intent",
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



LINKED_INTENT_SCHEMA = "YIM-001-LCC-INTENT/1"
LINKED_COMPLETION_SCHEMA = "YIM-001-LCC-COMPLETION/1"
LINKED_DIGEST_PREFIX = b"YIM-001-LCC-INITIAL-VERSION-DIGEST\x00v1\x00"
_LINKED_DIGEST_RE = re.compile(r"^[0-9a-f]{64}$")


def _canonical_jcs(value: Any) -> bytes:
    """Return RFC 8785 JCS UTF-8 bytes, rejecting values outside the profile."""
    _validate_json(value, "linked-create digest")
    try:
        encoded = rfc8785.dumps(value)
    except (TypeError, ValueError, UnicodeError) as exc:
        raise ProvenanceError(f"cannot canonicalize linked-create digest: {exc}") from exc
    if not isinstance(encoded, bytes):
        raise ProvenanceError("JCS canonicalizer did not return bytes")
    return encoded


def initial_version_digest(
    record_id: str,
    version_id: str,
    content: Any,
    metadata: Any = None,
) -> str:
    """Digest the exact initial-version envelope under YIM-001-LCC-JCS/1."""
    for field, value in (("record_id", record_id), ("version_id", version_id)):
        if not isinstance(value, str) or not value.strip():
            raise ProvenanceError(f"{field} must be a non-empty string")
    envelope = {
        "record_id": record_id,
        "version_id": version_id,
        "parents": [],
        "content": content,
        "metadata": metadata,
    }
    payload = LINKED_DIGEST_PREFIX + _canonical_jcs(envelope)
    return hashlib.sha256(payload).hexdigest()


def _validate_operation_id(value: Any) -> None:
    if not isinstance(value, str):
        raise ProvenanceError("linked-create operation_id must be a canonical UUIDv4")
    try:
        parsed = UUID(value)
    except (ValueError, AttributeError) as exc:
        raise ProvenanceError("linked-create operation_id must be a canonical UUIDv4") from exc
    if str(parsed) != value or parsed.version != 4:
        raise ProvenanceError("linked-create operation_id must be a canonical lowercase UUIDv4")


def _validate_linked_create_event(
    event_type: str,
    actor_id: str,
    record_id: str | None,
    version_id: str | None,
    details: Any,
) -> None:
    if event_type == "record.create_intent":
        schema = LINKED_INTENT_SCHEMA
        expected = {"schema", "operation_id", "record_digest"}
    elif (
        event_type == "record.created"
        and isinstance(details, dict)
        and details.get("schema") == LINKED_COMPLETION_SCHEMA
    ):
        schema = LINKED_COMPLETION_SCHEMA
        expected = {"schema", "operation_id", "intent_event_id", "record_digest"}
    else:
        return
    if not isinstance(actor_id, str) or not actor_id.strip():
        raise ProvenanceError("linked-create actor claim must be a non-empty string")
    if not isinstance(record_id, str) or not record_id.strip():
        raise ProvenanceError("linked-create record_id is required")
    if not isinstance(version_id, str) or not version_id.strip():
        raise ProvenanceError("linked-create version_id is required")
    if not isinstance(details, dict) or set(details) != expected or details.get("schema") != schema:
        raise ProvenanceError(f"invalid {schema} details schema")
    _validate_operation_id(details.get("operation_id"))
    digest = details.get("record_digest")
    if not isinstance(digest, str) or not _LINKED_DIGEST_RE.fullmatch(digest):
        raise ProvenanceError("linked-create record_digest must be 64 lowercase hexadecimal characters")
    if schema == LINKED_COMPLETION_SCHEMA:
        intent_event_id = details.get("intent_event_id")
        if not isinstance(intent_event_id, str) or not intent_event_id.strip():
            raise ProvenanceError("linked completion intent_event_id must be a non-empty string")


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
        _validate_linked_create_event(event_type, actor_id, record_id, version_id, details_copy)
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
            _validate_linked_create_event(row["event_type"], row["actor_id"], row["record_id"], row["version_id"], row["details"])
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
