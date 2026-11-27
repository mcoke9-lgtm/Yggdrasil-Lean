"""Append-only observation, tracking, assessment, and adoption for YIM-001.

The register records attributed events and derives each item's lifecycle state.
It does not poll sources, authenticate actors, or authorize external actions.
"""
from __future__ import annotations

from copy import deepcopy
from datetime import datetime, timezone
import hashlib
import json
import os
from pathlib import Path
import tempfile
from typing import Any


class TrackingRegisterError(ValueError):
    """Raised when a tracking event or snapshot is invalid."""


def _canonical(value: Any) -> bytes:
    return json.dumps(
        value, ensure_ascii=False, allow_nan=False, sort_keys=True,
        separators=(",", ":"),
    ).encode("utf-8")


def _timestamp(value: Any, field: str) -> None:
    if not isinstance(value, str) or not value.strip():
        raise TrackingRegisterError(f"{field} must be a timezone-aware ISO-8601 string")
    try:
        parsed = datetime.fromisoformat(value.replace("Z", "+00:00"))
    except ValueError as exc:
        raise TrackingRegisterError(f"{field} must be a timezone-aware ISO-8601 string") from exc
    if parsed.tzinfo is None or parsed.utcoffset() is None:
        raise TrackingRegisterError(f"{field} must include a timezone")


def _now() -> str:
    return datetime.now(timezone.utc).isoformat().replace("+00:00", "Z")


def _text(value: Any, field: str, *, nullable: bool = False) -> None:
    if nullable and value is None:
        return
    if not isinstance(value, str) or not value.strip():
        raise TrackingRegisterError(f"{field} must be a non-empty string")


def _string_list(value: Any, field: str) -> None:
    if not isinstance(value, list) or not all(
        isinstance(item, str) and item.strip() for item in value
    ):
        raise TrackingRegisterError(f"{field} must be a list of non-empty strings")


class TrackingRegister:
    FORMAT = "YIM-001-Tracking-Register"
    FORMAT_VERSION = 3
    SUPPORTED_FORMAT_VERSIONS = {1, 2, 3}
    EVENT_TYPES = {
        "item.observed", "tracking.started", "update.captured",
        "assessment.recorded", "adoption.authorized", "correction.appended",
        "source.recovery.recorded",
    }
    STATES = ("Observed", "Tracking", "Assessed", "Adopted")

    def __init__(self, registry_id: str, events: list[dict[str, Any]] | None = None):
        _text(registry_id, "registry_id")
        self.registry_id = registry_id
        self._events: list[dict[str, Any]] = []
        if events is not None:
            self._validate_and_load(events)

    @property
    def events(self) -> list[dict[str, Any]]:
        return deepcopy(self._events)

    def _append(
        self, event_id: str, event_type: str, actor_id: str,
        timestamp: str | None, payload: dict[str, Any],
    ) -> dict[str, Any]:
        _text(event_id, "event_id")
        _text(actor_id, "actor_id")
        timestamp = _now() if timestamp is None else timestamp
        _timestamp(timestamp, "timestamp")
        if event_type not in self.EVENT_TYPES:
            raise TrackingRegisterError("unsupported event_type")
        previous = self._events[-1]["event_hash"] if self._events else None
        core = {
            "sequence": len(self._events) + 1,
            "event_id": event_id,
            "event_type": event_type,
            "actor_id": actor_id,
            "timestamp": timestamp,
            "payload": deepcopy(payload),
            "previous_event_hash": previous,
        }
        event = {**core, "event_hash": hashlib.sha256(_canonical(core)).hexdigest()}
        self._validate_and_load(self._events + [event])
        return deepcopy(self._events[-1])

    def _validate_and_load(self, events: list[dict[str, Any]]) -> None:
        if not isinstance(events, list):
            raise TrackingRegisterError("events must be a list")
        seen_events: set[str] = set()
        items: dict[str, str] = {}
        item_events: dict[str, set[str]] = {}
        previous_hash = None
        for index, event in enumerate(events, start=1):
            fields = {
                "sequence", "event_id", "event_type", "actor_id", "timestamp",
                "payload", "previous_event_hash", "event_hash",
            }
            if not isinstance(event, dict) or set(event) != fields:
                raise TrackingRegisterError("invalid event structure")
            if type(event["sequence"]) is not int or event["sequence"] != index:
                raise TrackingRegisterError("event sequence must be contiguous and one-based")
            _text(event["event_id"], "event_id")
            _text(event["actor_id"], "actor_id")
            _timestamp(event["timestamp"], "timestamp")
            if event["event_id"] in seen_events:
                raise TrackingRegisterError("duplicate event_id")
            seen_events.add(event["event_id"])
            if event["event_type"] not in self.EVENT_TYPES or not isinstance(event["payload"], dict):
                raise TrackingRegisterError("unsupported event or invalid payload")
            if event["previous_event_hash"] != previous_hash:
                raise TrackingRegisterError("event history link mismatch")
            core = {key: event[key] for key in (
                "sequence", "event_id", "event_type", "actor_id", "timestamp",
                "payload", "previous_event_hash",
            )}
            expected_hash = hashlib.sha256(_canonical(core)).hexdigest()
            if event["event_hash"] != expected_hash:
                raise TrackingRegisterError("event hash mismatch")

            kind, payload = event["event_type"], event["payload"]
            tracking_id = payload.get("tracking_id")
            _text(tracking_id, "tracking_id")

            if kind == "item.observed":
                legacy_required = {
                    "tracking_id", "source_title", "source_url", "publisher",
                    "published_at", "captured_at", "user_note", "keywords",
                }
                current_required = legacy_required | {"source_recovery_status", "retrieval_notes"}
                if set(payload) not in (legacy_required, current_required):
                    raise TrackingRegisterError("invalid item.observed payload")
                if tracking_id in items:
                    raise TrackingRegisterError("duplicate tracking_id")
                _text(payload["source_title"], "source_title")
                for field in ("source_url", "publisher", "published_at", "user_note"):
                    _text(payload[field], field, nullable=True)
                if payload["published_at"] is not None:
                    _timestamp(payload["published_at"], "published_at")
                _timestamp(payload["captured_at"], "captured_at")
                _string_list(payload["keywords"], "keywords")
                if "source_recovery_status" in payload:
                    if payload["source_recovery_status"] not in {"unknown", "available", "partial", "unavailable"}:
                        raise TrackingRegisterError("invalid source_recovery_status")
                    _text(payload["retrieval_notes"], "retrieval_notes", nullable=True)
                items[tracking_id] = "Observed"
                item_events[tracking_id] = {event["event_id"]}

            elif tracking_id not in items:
                raise TrackingRegisterError("event references unknown tracking_id")

            elif kind == "tracking.started":
                if set(payload) != {"tracking_id"} or items[tracking_id] != "Observed":
                    raise TrackingRegisterError("tracking can start only once from Observed")
                items[tracking_id] = "Tracking"
                item_events[tracking_id].add(event["event_id"])

            elif kind == "source.recovery.recorded":
                required = {"tracking_id", "status", "retrieval_notes", "source_ref"}
                if set(payload) != required:
                    raise TrackingRegisterError("invalid source.recovery.recorded payload")
                if payload["status"] not in {"unknown", "available", "partial", "unavailable"}:
                    raise TrackingRegisterError("invalid source recovery status")
                _text(payload["retrieval_notes"], "retrieval_notes", nullable=True)
                _text(payload["source_ref"], "source_ref", nullable=True)
                item_events[tracking_id].add(event["event_id"])

            elif kind == "update.captured":
                required = {"tracking_id", "update_note", "source_ref", "captured_at"}
                if set(payload) != required or items[tracking_id] != "Tracking":
                    raise TrackingRegisterError("updates require an item in Tracking state")
                _text(payload["update_note"], "update_note")
                _text(payload["source_ref"], "source_ref", nullable=True)
                _timestamp(payload["captured_at"], "captured_at")
                item_events[tracking_id].add(event["event_id"])

            elif kind == "assessment.recorded":
                required = {"tracking_id", "assessment", "assessed_by", "evidence_refs", "uncertainty"}
                if set(payload) != required or items[tracking_id] not in {"Observed", "Tracking"}:
                    raise TrackingRegisterError("assessment requires an unassessed item")
                _text(payload["assessment"], "assessment")
                _text(payload["assessed_by"], "assessed_by")
                _string_list(payload["evidence_refs"], "evidence_refs")
                _text(payload["uncertainty"], "uncertainty")
                items[tracking_id] = "Assessed"
                item_events[tracking_id].add(event["event_id"])

            elif kind == "adoption.authorized":
                required = {
                    "tracking_id", "target", "decision", "authorized_by",
                    "authorization_ref", "explicitly_authorized",
                }
                if set(payload) != required or items[tracking_id] != "Assessed":
                    raise TrackingRegisterError("adoption requires an assessed item")
                for field in ("target", "decision", "authorized_by", "authorization_ref"):
                    _text(payload[field], field)
                if payload["explicitly_authorized"] is not True:
                    raise TrackingRegisterError("adoption requires explicit authorization")
                items[tracking_id] = "Adopted"
                item_events[tracking_id].add(event["event_id"])

            elif kind == "correction.appended":
                required = {"tracking_id", "target_event_id", "correction", "source_ref"}
                if set(payload) != required:
                    raise TrackingRegisterError("invalid correction.appended payload")
                if payload["target_event_id"] not in item_events[tracking_id]:
                    raise TrackingRegisterError("correction target must be an event for this item")
                _text(payload["correction"], "correction")
                _text(payload["source_ref"], "source_ref", nullable=True)
                item_events[tracking_id].add(event["event_id"])

            previous_hash = event["event_hash"]
        self._events = deepcopy(events)

    def observe(
        self, *, tracking_id: str, source_title: str, event_id: str,
        actor_id: str, source_url: str | None = None, publisher: str | None = None,
        published_at: str | None = None, captured_at: str | None = None,
        user_note: str | None = None, keywords: list[str] | None = None,
        source_recovery_status: str = "unknown", retrieval_notes: str | None = None,
        timestamp: str | None = None,
    ) -> dict[str, Any]:
        return self._append(event_id, "item.observed", actor_id, timestamp, {
            "tracking_id": tracking_id, "source_title": source_title,
            "source_url": source_url, "publisher": publisher,
            "published_at": published_at, "captured_at": captured_at or _now(),
            "user_note": user_note, "keywords": [] if keywords is None else keywords,
            "source_recovery_status": source_recovery_status,
            "retrieval_notes": retrieval_notes,
        })

    def start_tracking(self, *, tracking_id: str, event_id: str,
                       actor_id: str, timestamp: str | None = None) -> dict[str, Any]:
        return self._append(event_id, "tracking.started", actor_id, timestamp, {
            "tracking_id": tracking_id,
        })

    def record_source_recovery(
        self, *, tracking_id: str, status: str, event_id: str, actor_id: str,
        retrieval_notes: str | None = None, source_ref: str | None = None,
        timestamp: str | None = None,
    ) -> dict[str, Any]:
        """Append a source retrieval outcome; never erase earlier recovery attempts."""
        return self._append(event_id, "source.recovery.recorded", actor_id, timestamp, {
            "tracking_id": tracking_id, "status": status,
            "retrieval_notes": retrieval_notes, "source_ref": source_ref,
        })

    def capture_update(self, *, tracking_id: str, update_note: str,
                       event_id: str, actor_id: str, source_ref: str | None = None,
                       captured_at: str | None = None,
                       timestamp: str | None = None) -> dict[str, Any]:
        return self._append(event_id, "update.captured", actor_id, timestamp, {
            "tracking_id": tracking_id, "update_note": update_note,
            "source_ref": source_ref, "captured_at": captured_at or _now(),
        })

    def assess(self, *, tracking_id: str, assessment: str, assessed_by: str,
               evidence_refs: list[str], uncertainty: str, event_id: str,
               actor_id: str, timestamp: str | None = None) -> dict[str, Any]:
        return self._append(event_id, "assessment.recorded", actor_id, timestamp, {
            "tracking_id": tracking_id, "assessment": assessment,
            "assessed_by": assessed_by, "evidence_refs": evidence_refs,
            "uncertainty": uncertainty,
        })

    def authorize_adoption(self, *, tracking_id: str, target: str, decision: str,
                           authorized_by: str, authorization_ref: str,
                           explicitly_authorized: bool, event_id: str,
                           actor_id: str, timestamp: str | None = None) -> dict[str, Any]:
        return self._append(event_id, "adoption.authorized", actor_id, timestamp, {
            "tracking_id": tracking_id, "target": target, "decision": decision,
            "authorized_by": authorized_by, "authorization_ref": authorization_ref,
            "explicitly_authorized": explicitly_authorized,
        })

    def append_correction(self, *, tracking_id: str, target_event_id: str,
                          correction: str, event_id: str, actor_id: str,
                          source_ref: str | None = None,
                          timestamp: str | None = None) -> dict[str, Any]:
        return self._append(event_id, "correction.appended", actor_id, timestamp, {
            "tracking_id": tracking_id, "target_event_id": target_event_id,
            "correction": correction, "source_ref": source_ref,
        })

    def items(self) -> list[dict[str, Any]]:
        result: dict[str, dict[str, Any]] = {}
        for event in self._events:
            payload = event["payload"]
            tracking_id = payload["tracking_id"]
            if event["event_type"] == "item.observed":
                result[tracking_id] = {
                    **deepcopy(payload),
                    "source_recovery_status": payload.get("source_recovery_status", "unknown"),
                    "retrieval_notes": payload.get("retrieval_notes"),
                    "state": "Observed", "event_ids": [event["event_id"]],
                    "source_recovery_history": [{
                        "status": payload.get("source_recovery_status", "unknown"),
                        "retrieval_notes": payload.get("retrieval_notes"),
                        "source_ref": payload.get("source_url"),
                        "event_id": event["event_id"],
                        "recorded_at": event["timestamp"],
                        "actor_id": event["actor_id"],
                        "event_type": "item.observed",
                    }],
                }
            else:
                row = result[tracking_id]
                row["event_ids"].append(event["event_id"])
                if event["event_type"] == "tracking.started":
                    row["state"] = "Tracking"
                elif event["event_type"] == "assessment.recorded":
                    row["state"] = "Assessed"
                    row["latest_assessment"] = deepcopy(payload)
                elif event["event_type"] == "adoption.authorized":
                    row["state"] = "Adopted"
                    row["adoption"] = deepcopy(payload)
                elif event["event_type"] == "source.recovery.recorded":
                    row["source_recovery_status"] = payload["status"]
                    row["retrieval_notes"] = payload["retrieval_notes"]
                    row.setdefault("source_recovery_history", []).append({
                        **deepcopy(payload), "event_id": event["event_id"],
                        "recorded_at": event["timestamp"], "actor_id": event["actor_id"],
                        "event_type": event["event_type"],
                    })
                elif event["event_type"] == "update.captured":
                    row.setdefault("updates", []).append(deepcopy(payload))
                elif event["event_type"] == "correction.appended":
                    row.setdefault("corrections", []).append(deepcopy(payload))
        return list(result.values())

    def search(self, query: str = "", *, states: list[str] | None = None) -> list[dict[str, Any]]:
        """Search indexed item text and return matching items with their event history."""
        if not isinstance(query, str):
            raise TrackingRegisterError("query must be a string")
        if states is not None and (not isinstance(states, list) or any(state not in self.STATES for state in states)):
            raise TrackingRegisterError("states must contain valid lifecycle states")
        needle = query.casefold().strip()
        rows = self.items()
        history: dict[str, list[dict[str, Any]]] = {}
        for event in self._events:
            history.setdefault(event["payload"]["tracking_id"], []).append(deepcopy(event))
        matches = []
        for row in rows:
            if states is not None and row["state"] not in states:
                continue
            row_history = history[row["tracking_id"]]
            searchable = json.dumps(
                {"item": row, "event_history": row_history},
                ensure_ascii=False, sort_keys=True,
            ).casefold()
            if needle and needle not in searchable:
                continue
            row["event_history"] = row_history
            matches.append(row)
        return matches

    def export_json(self) -> str:
        """Export the complete register snapshot, including every lifecycle event."""
        return json.dumps(self.to_snapshot(), ensure_ascii=False, allow_nan=False,
                          sort_keys=True, indent=2) + "\n"

    def to_snapshot(self) -> dict[str, Any]:
        snapshot = {
            "format": self.FORMAT, "format_version": self.FORMAT_VERSION,
            "registry_id": self.registry_id, "events": self.events,
        }
        snapshot["snapshot_hash"] = hashlib.sha256(_canonical({
            "registry_id": self.registry_id, "events": snapshot["events"],
        })).hexdigest()
        return snapshot

    @classmethod
    def from_snapshot(cls, snapshot: Any) -> "TrackingRegister":
        if not isinstance(snapshot, dict):
            raise TrackingRegisterError("invalid register snapshot structure")
        legacy_fields = {"format", "format_version", "registry_id", "events"}
        current_fields = legacy_fields | {"snapshot_hash"}
        version = snapshot.get("format_version")
        if (version in {1, 2} and set(snapshot) != legacy_fields) or (
            version == 3 and set(snapshot) != current_fields
        ):
            raise TrackingRegisterError("invalid register snapshot structure")
        if (
            snapshot["format"] != cls.FORMAT
            or type(snapshot["format_version"]) is not int
            or snapshot["format_version"] not in cls.SUPPORTED_FORMAT_VERSIONS
        ):
            raise TrackingRegisterError("unsupported register format or version")
        try:
            if version == 3:
                _text(snapshot["registry_id"], "registry_id")
                expected_snapshot_hash = hashlib.sha256(_canonical({
                    "registry_id": snapshot["registry_id"], "events": snapshot["events"],
                })).hexdigest()
                if not isinstance(snapshot["snapshot_hash"], str) or snapshot["snapshot_hash"] != expected_snapshot_hash:
                    raise TrackingRegisterError("snapshot integrity hash mismatch")
            return cls(snapshot["registry_id"], snapshot["events"])
        except TrackingRegisterError:
            raise
        except (TypeError, KeyError, ValueError, OverflowError) as exc:
            raise TrackingRegisterError(f"malformed register snapshot: {exc}") from exc


def save_register(register: TrackingRegister, path: str | Path) -> None:
    """Atomically save a single-writer JSON snapshot; no multi-writer locking."""
    if not isinstance(register, TrackingRegister):
        raise TypeError("register must be a TrackingRegister")
    target = Path(path)
    target.parent.mkdir(parents=True, exist_ok=True)
    encoded = json.dumps(
        register.to_snapshot(), ensure_ascii=False, allow_nan=False,
        sort_keys=True, indent=2,
    ) + "\n"
    temporary_name = None
    try:
        with tempfile.NamedTemporaryFile(
            "w", encoding="utf-8", newline="\n", dir=target.parent,
            prefix=f".{target.name}.", suffix=".tmp", delete=False,
        ) as handle:
            temporary_name = handle.name
            handle.write(encoded)
            handle.flush()
            os.fsync(handle.fileno())
        os.replace(temporary_name, target)
    finally:
        if temporary_name is not None and os.path.exists(temporary_name):
            os.unlink(temporary_name)


def load_register(path: str | Path) -> TrackingRegister:
    """Load and validate a saved register snapshot and its event hash chain."""
    try:
        with Path(path).open("r", encoding="utf-8") as handle:
            snapshot = json.load(handle)
    except (OSError, UnicodeError, json.JSONDecodeError) as exc:
        raise TrackingRegisterError(f"register could not be read: {exc}") from exc
    return TrackingRegister.from_snapshot(snapshot)
