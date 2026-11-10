"""Portable, read-only cross-instance transfer inspection for YIM-001.

Transfer envelopes preserve complete audited snapshots. Staging creates a
deterministic, detached reconciliation proposal and never mutates either side.
Applying a proposal is deliberately deferred.
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
        return json.dumps(value, ensure_ascii=False, allow_nan=False, sort_keys=True, separators=(",", ":")).encode("utf-8")
    except (TypeError, ValueError, UnicodeError) as exc:
        raise ExchangeError(f"invalid transfer JSON: {exc}") from exc


def _digest(payload: dict[str, Any]) -> str:
    return hashlib.sha256(_canonical(payload)).hexdigest()


def export_envelope(source: AuditedMemoryStore, source_instance_id: str, *, exported_at: str | None = None) -> dict[str, Any]:
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
        "format": FORMAT, "format_version": FORMAT_VERSION,
        "source_instance_id": source_instance_id, "exported_at": stamp,
        "audited_snapshot": source.to_snapshot(),
    }
    return {"payload": payload, "sha256": _digest(payload)}


def save_envelope(envelope: dict[str, Any], path: str | Path) -> None:
    inspect_envelope(envelope)
    write_json_atomic(path, envelope)


def load_envelope(path: str | Path) -> dict[str, Any]:
    return inspect_envelope(read_json_strict(path))


def inspect_envelope(envelope: Any) -> dict[str, Any]:
    """Validate digest, nested audited snapshot, and return detached data."""
    if not isinstance(envelope, dict) or set(envelope) != {"payload", "sha256"}:
        raise ExchangeError("invalid transfer envelope structure")
    payload = envelope["payload"]
    required = {"format", "format_version", "source_instance_id", "exported_at", "audited_snapshot"}
    if not isinstance(payload, dict) or set(payload) != required:
        raise ExchangeError("invalid transfer payload structure")
    if payload["format"] != FORMAT or type(payload["format_version"]) is not int or payload["format_version"] != FORMAT_VERSION:
        raise ExchangeError("unsupported transfer format or version")
    source_id, stamp = payload["source_instance_id"], payload["exported_at"]
    if not isinstance(source_id, str) or not source_id.strip():
        raise ExchangeError("invalid source instance identity")
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
    return {"source_instance_id": source_id, "exported_at": stamp, "audited_snapshot": audited.to_snapshot(), "sha256": expected}


def _index(rows: list[dict[str, Any]], key: str) -> dict[str, dict[str, Any]]:
    return {row[key]: row for row in rows}


def _tips(versions: dict[str, dict[str, Any]]) -> list[str]:
    parents = {parent for row in versions.values() for parent in row["parents"]}
    return sorted(set(versions) - parents)


def stage_exchange(envelope: Any, destination: AuditedMemoryStore) -> dict[str, Any]:
    """Create a deterministic reconciliation proposal without mutating either instance."""
    incoming = inspect_envelope(envelope)
    if not isinstance(destination, AuditedMemoryStore):
        raise TypeError("destination must be an AuditedMemoryStore")
    source_snapshot, dest_snapshot = incoming["audited_snapshot"], destination.to_snapshot()
    src_mem, dst_mem = source_snapshot["memory"], dest_snapshot["memory"]
    src_versions, dst_versions = _index(src_mem["versions"], "version_id"), _index(dst_mem["versions"], "version_id")
    src_events, dst_events = _index(source_snapshot["provenance"]["events"], "event_id"), _index(dest_snapshot["provenance"]["events"], "event_id")
    src_records, dst_records = _index(src_mem["records"], "record_id"), _index(dst_mem["records"], "record_id")

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

    # A stable record ID with different version membership is expected when
    # instances diverge; the differing version graph is described separately.
    incoming_records = sorted(set(src_records) - set(dst_records))
    shared_records = sorted(set(src_records) & set(dst_records))
    all_version_ids = set(src_versions) | set(dst_versions)
    unresolved_parents = sorted({
        parent for identity in version_additions
        for parent in src_versions[identity]["parents"] if parent not in all_version_ids
    })
    parent_edges = sorted(
        [{"version_id": identity, "parent_version_id": parent}
         for identity in version_additions for parent in src_versions[identity]["parents"]],
        key=lambda edge: (edge["version_id"], edge["parent_version_id"]),
    )
    source_tips, destination_tips = _tips(src_versions), _tips(dst_versions)

    dest_conflicts = {(row["source_version_id"], row["target_version_id"]) for row in dst_mem["conflicts"]}
    incoming_conflicts, shared_conflicts = [], []
    for row in src_mem["conflicts"]:
        pair = (row["source_version_id"], row["target_version_id"])
        target = shared_conflicts if pair in dest_conflicts else incoming_conflicts
        target.append({"source_version_id": pair[0], "target_version_id": pair[1]})
    incoming_conflicts.sort(key=lambda row: (row["source_version_id"], row["target_version_id"]))
    shared_conflicts.sort(key=lambda row: (row["source_version_id"], row["target_version_id"]))

    hard_collisions = bool(version_collisions or event_collisions)
    status = "identity_collision" if hard_collisions else ("incomplete_parent_history" if unresolved_parents else "staged_read_only")
    source_events = source_snapshot["provenance"]["events"]
    return {
        "status": status,
        "source_instance_id": incoming["source_instance_id"],
        "destination_actor_id": destination.actor_id,
        "incoming_record_ids": incoming_records,
        "shared_record_ids": shared_records,
        "incoming_version_ids": sorted(version_additions),
        "shared_version_ids": sorted(shared_versions),
        "version_identity_collisions": sorted(version_collisions),
        "incoming_parent_edges": parent_edges,
        "unresolved_parent_version_ids": unresolved_parents,
        "source_history_tips": source_tips,
        "destination_history_tips": destination_tips,
        "divergent_shared_record_histories": sorted(
            identity for identity in set(src_records) & set(dst_records)
            if set(src_records[identity]["version_ids"]) != set(dst_records[identity]["version_ids"])
        ),
        "incoming_directional_conflicts": incoming_conflicts,
        "shared_directional_conflicts": shared_conflicts,
        "incoming_event_ids": sorted(event_additions),
        "shared_event_ids": sorted(shared_events),
        "event_identity_collisions": sorted(event_collisions),
        "source_event_stream": {
            "source_instance_id": incoming["source_instance_id"],
            "event_count": len(source_events),
            "first_sequence": source_events[0]["sequence"] if source_events else None,
            "last_sequence": source_events[-1]["sequence"] if source_events else None,
        },
        "mutated_destination": False, "mutated_source": False,
        "automatic_merge": False, "apply_authorized": False,
    }
