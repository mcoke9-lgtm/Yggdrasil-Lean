"""Persistent, source-neutral work intake packets awaiting Living Tree placement.

This queue is a local handoff boundary. It preserves submitted JSON content and
source metadata, but does not discover ChatGPT history or import packets into an
audited memory store automatically.
"""
from __future__ import annotations

from datetime import datetime, timezone
from pathlib import Path
from typing import Any
from uuid import uuid4

from .core import _snapshot, _validate_json
from .storage import read_json_strict, write_json_atomic


class IntakeQueueError(ValueError):
    """Raised when an intake queue or packet is invalid."""


class WorkIntakeQueue:
    FORMAT = "YIM-001-Work-Intake-Queue"
    FORMAT_VERSION = 1

    def __init__(self, queue_id: str, packets: list[dict[str, Any]] | None = None) -> None:
        if not isinstance(queue_id, str) or not queue_id.strip():
            raise IntakeQueueError("queue_id must be a non-empty caller-supplied identifier")
        self.queue_id = queue_id
        self._packets = [] if packets is None else _snapshot(packets, "packets")
        self._validate_packets()

    @property
    def packets(self) -> tuple[dict[str, Any], ...]:
        return tuple(_snapshot(packet, "packet") for packet in self._packets)

    def add(
        self,
        content: Any,
        *,
        source_kind: str,
        language: str,
        source_reference: str | None = None,
        metadata: Any = None,
        captured_at: str | None = None,
        packet_id: str | None = None,
    ) -> dict[str, Any]:
        if not isinstance(source_kind, str) or not source_kind.strip():
            raise IntakeQueueError("source_kind must be a non-empty description")
        if not isinstance(language, str) or not language.strip():
            raise IntakeQueueError("language must be supplied or explicitly marked undetermined")
        if source_reference is not None and (
            not isinstance(source_reference, str) or not source_reference.strip()
        ):
            raise IntakeQueueError("source_reference must be null or a non-empty reference")
        _validate_json(content, "intake content")
        _validate_json(metadata, "intake metadata")
        identifier = str(uuid4()) if packet_id is None else packet_id
        if not isinstance(identifier, str) or not identifier.strip():
            raise IntakeQueueError("packet_id must be a non-empty identifier")
        if any(packet["packet_id"] == identifier for packet in self._packets):
            raise IntakeQueueError(f"duplicate intake packet: {identifier}")
        stamp = captured_at or datetime.now(timezone.utc).isoformat().replace("+00:00", "Z")
        if not isinstance(stamp, str) or not stamp.strip():
            raise IntakeQueueError("captured_at must be an ISO-8601 timestamp")
        try:
            datetime.fromisoformat(stamp.replace("Z", "+00:00"))
        except ValueError as exc:
            raise IntakeQueueError("captured_at must be an ISO-8601 timestamp") from exc
        packet = {
            "packet_id": identifier,
            "captured_at": stamp,
            "source_kind": source_kind,
            "source_reference": source_reference,
            "language": language,
            "content": _snapshot(content, "content"),
            "metadata": _snapshot({} if metadata is None else metadata, "metadata"),
            "placement_status": "awaiting_placement",
            "primary_logical_location": None,
            "contextual_logical_locations": [],
            "source_content_preserved_as_submitted": True,
        }
        self._packets.append(packet)
        return _snapshot(packet, "packet")

    def pending(self) -> tuple[dict[str, Any], ...]:
        return tuple(
            _snapshot(packet, "packet") for packet in self._packets
            if packet["placement_status"] == "awaiting_placement"
        )

    def to_snapshot(self) -> dict[str, Any]:
        return {
            "format": self.FORMAT,
            "format_version": self.FORMAT_VERSION,
            "queue_id": self.queue_id,
            "packets": _snapshot(self._packets, "packets"),
        }

    @classmethod
    def from_snapshot(cls, snapshot: Any) -> "WorkIntakeQueue":
        required = {"format", "format_version", "queue_id", "packets"}
        if not isinstance(snapshot, dict) or set(snapshot) != required:
            raise IntakeQueueError("invalid work intake queue structure")
        if snapshot["format"] != cls.FORMAT or type(snapshot["format_version"]) is not int or snapshot["format_version"] != cls.FORMAT_VERSION:
            raise IntakeQueueError("unsupported work intake queue format or version")
        if not isinstance(snapshot["packets"], list):
            raise IntakeQueueError("packets must be an array")
        return cls(snapshot["queue_id"], snapshot["packets"])

    def _validate_packets(self) -> None:
        seen: set[str] = set()
        required = {
            "packet_id", "captured_at", "source_kind", "source_reference",
            "language", "content", "metadata", "placement_status",
            "primary_logical_location", "contextual_logical_locations",
            "source_content_preserved_as_submitted",
        }
        for index, packet in enumerate(self._packets):
            if not isinstance(packet, dict) or set(packet) != required:
                raise IntakeQueueError(f"invalid intake packet at index {index}")
            identifier = packet["packet_id"]
            if not isinstance(identifier, str) or not identifier.strip() or identifier in seen:
                raise IntakeQueueError(f"invalid or duplicate packet identity at index {index}")
            if packet["placement_status"] != "awaiting_placement":
                raise IntakeQueueError(f"unsupported placement status at index {index}")
            if not isinstance(packet["captured_at"], str) or not packet["captured_at"].strip():
                raise IntakeQueueError(f"invalid capture timestamp at index {index}")
            try:
                datetime.fromisoformat(packet["captured_at"].replace("Z", "+00:00"))
            except ValueError as exc:
                raise IntakeQueueError(f"invalid capture timestamp at index {index}") from exc
            if packet["primary_logical_location"] is not None or packet["contextual_logical_locations"] != []:
                raise IntakeQueueError(f"unplaced packet contains Tree placements at index {index}")
            if packet["source_content_preserved_as_submitted"] is not True:
                raise IntakeQueueError(f"source preservation marker is invalid at index {index}")
            if not isinstance(packet["source_kind"], str) or not packet["source_kind"].strip():
                raise IntakeQueueError(f"invalid source kind at index {index}")
            if not isinstance(packet["language"], str) or not packet["language"].strip():
                raise IntakeQueueError(f"invalid language at index {index}")
            if packet["source_reference"] is not None and (
                not isinstance(packet["source_reference"], str) or not packet["source_reference"].strip()
            ):
                raise IntakeQueueError(f"invalid source reference at index {index}")
            _validate_json(packet["content"], f"packets[{index}].content")
            _validate_json(packet["metadata"], f"packets[{index}].metadata")
            seen.add(identifier)


def create_intake_queue(path: str | Path, queue_id: str) -> WorkIntakeQueue:
    destination = Path(path)
    if destination.exists():
        raise FileExistsError(f"refusing to overwrite existing intake queue: {destination}")
    queue = WorkIntakeQueue(queue_id)
    write_json_atomic(destination, queue.to_snapshot())
    return queue


def load_intake_queue(path: str | Path) -> WorkIntakeQueue:
    return WorkIntakeQueue.from_snapshot(read_json_strict(path))


def save_intake_queue(queue: WorkIntakeQueue, path: str | Path) -> None:
    if not isinstance(queue, WorkIntakeQueue):
        raise TypeError("queue must be a WorkIntakeQueue")
    write_json_atomic(path, queue.to_snapshot())
