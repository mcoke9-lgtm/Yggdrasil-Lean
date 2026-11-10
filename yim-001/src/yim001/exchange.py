"""Portable, read-only cross-instance transfer inspection for YIM-001.

This module defines transfer envelopes and deterministic staging reports. It
never mutates the destination; applying a reconciliation plan is deferred.
"""
from __future__ import annotations

import hashlib
import json
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

from .audited import AuditedMemoryStore
from .core import _validate_json
from .storage import read_json_strict, write_json_atomic

FORMAT = "YIM-001-Transfer-Envelope"
FORMAT_VERSION = 1


class ExchangeError(ValueError):
    """Raised when a transfer envelope or staging operation is invalid."""


def _canonical(value: Any) -> bytes:
    _validate_json(value, "transfer")
    try:
        return json.dumps(
            value, ensure_ascii=False, allow_nan=False,
            sort_keys=True, separators=(",", ":"),
        ).encode("utf-8")
    except (TypeError, ValueError, UnicodeError) as exc:
        raise ExchangeError(f"invalid transfer JSON: {exc}") from exc


def _digest(payload: dict[str, Any]) -> str:
    return hashlib.sha256(_canonical(payload)).hexdigest()


def export_envelope(
    source: AuditedMemoryStore,
    source_instance_id: str,
    *,
    exported_at: str | None = None,
) -> dict[str, Any]:
    """Return a detached, digest-protected transfer envelope."""
    if not isinstance(source, AuditedMemoryStore):
        raise TypeError("source must be an AuditedMemoryStore")
    if not isinstance(source_instance_id, str) or not source_instance_id.strip():
        raise ExchangeError("source_instance_id must be a non-empty caller-supplied identifier")
    stamp = exported_at or datetime.now(timezone.utc).isoformat(timespec="microseconds")
    if not isinstance(stamp, str) or not stamp:
        raise ExchangeError("exported_at must be a timezone-qualified ISO-8601 string")
    try:
        parsed = datetime.fromisoformat(stamp.replace("Z", "+00:00"))
    except ValueError as exc:
        raise ExchangeError("exported_at must be valid ISO-8601") from exc
    if parsed.tzinfo is None or parsed.utcoffset() is None:
        raise ExchangeError("exported_at must include a UTC offset")
    payload = {
        "format": FORMAT,
        "format_version": FORMAT_VERSION,
        "source_instance_id": source_instance_id,
        "exported_at": stamp,
        "audited_snapshot": source.to_snapshot(),
    }
    return {"payload": payload, "sha256": _digest(payload)}


def save_envelope(envelope: dict[str, Any], path: str | Path) -> None:
    """Validate and atomically save a transfer envelope."""
    inspect_envelope(envelope)
    write_json_atomic(path, envelope)


def load_envelope(path: str | Path) -> dict[str, Any]:
    """Load a strict-JSON transfer envelope and validate it."""
    return inspect_envelope(read_json_strict(path))


def inspect_envelope(envelope: Any) -> dict[str, Any]:
    """Validate the envelope and return a detached validated snapshot."""
    if not isinstance(envelope, dict) or set(envelope) != {"payload", "sha256"}:
        raise ExchangeError("invalid transfer envelope structure")
    payload = envelope["payload"]
    required = {"format", "format_version", "source_instance_id", "exported_at", "audited_snapshot"}
    if not isinstance(payload, dict) or set(payload) != required:
        raise ExchangeError("invalid transfer payload structure")
    if payload["format"] != FORMAT or type(payload["format_version"]) is not int or payload["format_version"] != FORMAT_VERSION:
        raise ExchangeError("unsupported transfer format or version")
    source_id = payload["source_instance_id"]
    if not isinstance(source_id, str) or not source_id.strip():
        raise ExchangeError("invalid source instance identity")
    stamp = payload["exported_at"]
    if not isinstance(stamp, str) or not stamp:
        raise ExchangeError("invalid export timestamp")
    try:
        parsed = datetime.fromisoformat(stamp.replace("Z", "+00:00"))
    except ValueError as exc:
        raise ExchangeError("invalid export timestamp") from exc
    if parsed.tzinfo is None or parsed.utcoffset() is None:
        raise ExchangeError("export timestamp must include a UTC offset")
    expected = _digest(payload)
    if not isinstance(envelope["sha256"], str) or envelope["sha256"] != expected:
        raise ExchangeError("transfer envelope digest mismatch")
    audited = AuditedMemoryStore.from_snapshot(payload["audited_snapshot"])
    # Re-materialize canonical data so callers receive detached validated data.
    return {
        "source_instance_id": source_id,
        "exported_at": stamp,
        "audited_snapshot": audited.to_snapshot(),
        "sha256": expected,
    }


def _index(rows: list[dict[str, Any]], key: str) -> dict[str, dict[str, Any]]:
    return {row[key]: row for row in rows}


def stage_exchange(envelope: Any, destination: AuditedMemoryStore) -> dict[str, Any]:
    """Compare incoming evidence with a destination without changing either."""
    incoming = inspect_envelope(envelope)
    if not isinstance(destination, AuditedMemoryStore):
        raise TypeError("destination must be an AuditedMemoryStore")
    source_snapshot = incoming["audited_snapshot"]
    dest_snapshot = destination.to_snapshot()
    src_mem, dst_mem = source_snapshot["memory"], dest_snapshot["memory"]
    src_versions = _index(src_mem["versions"], "version_id")
    dst_versions = _index(dst_mem["versions"], "version_id")
    src_events = _index(src_snapshot["provenance"]["events"], "event_id")
    dst_events = _index(dest_snapshot["provenance"]["events"], "event_id")

    version_additions, shared_versions, version_collisions = [], [], []
    for identity, row in src_versions.items():
        existing = dst_versions.get(identity)
        if existing is None:
            version_additions.append(identity)
        elif _canonical(existing) == _canonical(row):
            shared_versions.append(identity)
        else:
            version_collisions.append(identity)

    event_additions, shared_events, event_collisions = [], [], []
    for identity, row in src_events.items():
        existing = dst_events.get(identity)
        if existing is None:
            event_additions.append(identity)
        elif _canonical(existing) == _canonical(row):
            shared_events.append(identity)
        else:
            event_collisions.append(identity)

    src_records = _index(src_mem["records"], "record_id")
    dst_records = _index(dst_mem["records"], "record_id")
    record_collisions = [
        identity for identity, row in src_records.items()
        if identity in dst_records and _canonical(row) != _canonical(dst_records[identity])
    ]
    return {
        "status": "identity_collision" if version_collisions or event_collisions or record_collisions else "staged_read_only",
        "source_instance_id": incoming["source_instance_id"],
        "destination_actor_id": destination.actor_id,
        "incoming_record_ids": sorted(set(src_records) - set(dst_records)),
        "shared_record_ids": sorted(set(src_records) & set(dst_records) - set(record_collisions)),
        "record_identity_collisions": sorted(record_collisions),
        "incoming_version_ids": sorted(version_additions),
        "shared_version_ids": sorted(shared_versions),
        "version_identity_collisions": sorted(version_collisions),
        "incoming_event_ids": sorted(event_additions),
        "shared_event_ids": sorted(shared_events),
        "event_identity_collisions": sorted(event_collisions),
        "source_event_stream": {
            "source_instance_id": incoming["source_instance_id"],
            "event_count": len(source_snapshot["provenance"]["events"]),
            "first_sequence": 0 if src_events else None,
            "last_sequence": len(src_events) - 1 if src_events else None,
        },
        "mutated_destination": False,
        "automatic_merge": False,
    }
