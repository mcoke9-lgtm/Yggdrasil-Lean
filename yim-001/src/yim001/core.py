"""Small in-memory reference core for MRP-001 Revision 4.

This module implements the minimal records profile. Persistence, provenance,
networking, recovery, and reconciliation policy are deliberately out of scope.
"""
from __future__ import annotations

from dataclasses import dataclass
import json
import math
from typing import Any
from uuid import uuid4


class RecordError(ValueError):
    """Base exception for invalid record operations."""


class NotFoundError(RecordError):
    """Raised when a requested record or version does not exist."""


class ValidationError(RecordError):
    """Raised when content or metadata is not valid JSON data."""


def _validate_json(value: Any, path: str = "$", active: set[int] | None = None) -> None:
    """Recursively reject values outside the JSON data model."""
    if active is None:
        active = set()
    if value is None or isinstance(value, (str, bool, int)):
        return
    if isinstance(value, float):
        if not math.isfinite(value):
            raise ValidationError(f"non-finite number at {path}")
        return
    if isinstance(value, list):
        identity = id(value)
        if identity in active:
            raise ValidationError(f"cyclic value at {path}")
        active.add(identity)
        try:
            for index, item in enumerate(value):
                _validate_json(item, f"{path}[{index}]", active)
        finally:
            active.remove(identity)
        return
    if isinstance(value, dict):
        identity = id(value)
        if identity in active:
            raise ValidationError(f"cyclic value at {path}")
        active.add(identity)
        try:
            for key, item in value.items():
                if not isinstance(key, str):
                    raise ValidationError(f"object key at {path} is not a string")
                _validate_json(item, f"{path}.{key}", active)
        finally:
            active.remove(identity)
        return
    raise ValidationError(f"unsupported JSON value {type(value).__name__} at {path}")


def _snapshot(value: Any, field: str) -> Any:
    _validate_json(value, field)
    try:
        return json.loads(json.dumps(value, allow_nan=False, separators=(",", ":")))
    except (TypeError, ValueError, OverflowError) as exc:
        raise ValidationError(f"invalid JSON value in {field}: {exc}") from exc


@dataclass(frozen=True)
class Version:
    version_id: str
    record_id: str
    parents: tuple[str, ...]
    content_json: str
    metadata_json: str | None

    @property
    def content(self) -> Any:
        return json.loads(self.content_json)

    @property
    def metadata(self) -> Any:
        return None if self.metadata_json is None else json.loads(self.metadata_json)


@dataclass(frozen=True)
class Conflict:
    source_version_id: str
    target_version_id: str


class MemoryStore:
    """In-memory append-only record/version graph with directional conflicts."""

    SNAPSHOT_FORMAT = "YIM-001-Core-Memory-System"
    SNAPSHOT_VERSION = 1

    def __init__(self) -> None:
        self._versions: dict[str, Version] = {}
        self._records: dict[str, list[str]] = {}
        self._conflicts: list[Conflict] = []

    def to_snapshot(self) -> dict[str, Any]:
        """Return a detached, JSON-compatible snapshot of the complete store."""
        return {
            "format": self.SNAPSHOT_FORMAT,
            "format_version": self.SNAPSHOT_VERSION,
            "records": [
                {"record_id": record_id, "version_ids": list(version_ids)}
                for record_id, version_ids in self._records.items()
            ],
            "versions": [
                {
                    "version_id": version.version_id,
                    "record_id": version.record_id,
                    "parents": list(version.parents),
                    "content": version.content,
                    "metadata": version.metadata,
                }
                for version in self._versions.values()
            ],
            "conflicts": [
                {
                    "source_version_id": conflict.source_version_id,
                    "target_version_id": conflict.target_version_id,
                }
                for conflict in self._conflicts
            ],
        }

    @classmethod
    def from_snapshot(cls, data: Any) -> "MemoryStore":
        """Reconstruct a store after validating snapshot shape and graph links."""
        _validate_json(data, "snapshot")
        if not isinstance(data, dict) or set(data) != {
            "format", "format_version", "records", "versions", "conflicts"
        }:
            raise ValidationError("snapshot has an invalid top-level structure")
        if data["format"] != cls.SNAPSHOT_FORMAT or type(data["format_version"]) is not int or data["format_version"] != cls.SNAPSHOT_VERSION:
            raise ValidationError("unsupported snapshot format or version")
        if not all(isinstance(data[key], list) for key in ("records", "versions", "conflicts")):
            raise ValidationError("snapshot records, versions, and conflicts must be arrays")

        store = cls()
        version_rows: dict[str, Version] = {}
        for index, row in enumerate(data["versions"]):
            if not isinstance(row, dict) or set(row) != {
                "version_id", "record_id", "parents", "content", "metadata"
            }:
                raise ValidationError(f"invalid version entry at index {index}")
            version_id, record_id = row["version_id"], row["record_id"]
            parents = row["parents"]
            if not isinstance(version_id, str) or not version_id or not isinstance(record_id, str) or not record_id:
                raise ValidationError(f"invalid version or record identity at index {index}")
            if version_id in version_rows:
                raise ValidationError(f"duplicate version identity: {version_id}")
            if not isinstance(parents, list) or not all(isinstance(parent, str) and parent for parent in parents):
                raise ValidationError(f"invalid parent list for version {version_id}")
            if len(set(parents)) != len(parents):
                raise ValidationError(f"duplicate parent identity for version {version_id}")
            content_copy = _snapshot(row["content"], f"versions[{index}].content")
            metadata_copy = None if row["metadata"] is None else _snapshot(row["metadata"], f"versions[{index}].metadata")
            version_rows[version_id] = Version(
                version_id,
                record_id,
                tuple(parents),
                json.dumps(content_copy, allow_nan=False, separators=(",", ":")),
                None if metadata_copy is None else json.dumps(metadata_copy, allow_nan=False, separators=(",", ":")),
            )

        record_rows: dict[str, list[str]] = {}
        membership: dict[str, str] = {}
        for index, row in enumerate(data["records"]):
            if not isinstance(row, dict) or set(row) != {"record_id", "version_ids"}:
                raise ValidationError(f"invalid record entry at index {index}")
            record_id, version_ids = row["record_id"], row["version_ids"]
            if not isinstance(record_id, str) or not record_id or record_id in record_rows:
                raise ValidationError(f"invalid or duplicate record identity at index {index}")
            if not isinstance(version_ids, list) or not version_ids:
                raise ValidationError(f"record {record_id} must have a non-empty version list")
            if not all(isinstance(version_id, str) for version_id in version_ids):
                raise ValidationError(f"invalid version identity in record {record_id}")
            if len(set(version_ids)) != len(version_ids):
                raise ValidationError(f"duplicate version membership in record {record_id}")
            for version_id in version_ids:
                version = version_rows.get(version_id)
                if version is None or version.record_id != record_id or version_id in membership:
                    raise ValidationError(f"invalid version membership: {version_id}")
                membership[version_id] = record_id
            record_rows[record_id] = list(version_ids)

        if set(membership) != set(version_rows):
            raise ValidationError("snapshot contains unassigned versions")

        for record_id, version_ids in record_rows.items():
            positions = {version_id: index for index, version_id in enumerate(version_ids)}
            for index, child_id in enumerate(version_ids):
                parents = version_rows[child_id].parents
                if index == 0 and parents:
                    raise ValidationError(f"initial version {child_id} cannot have parents")
                if index > 0 and not parents:
                    raise ValidationError(f"non-initial version {child_id} must have at least one parent")
                for parent_id in parents:
                    if parent_id not in positions or positions[parent_id] >= positions[child_id]:
                        raise ValidationError(f"invalid parent ordering for version {child_id}")

        store._versions = version_rows
        store._records = record_rows

        seen_conflicts: set[tuple[str, str]] = set()
        for index, row in enumerate(data["conflicts"]):
            if not isinstance(row, dict) or set(row) != {"source_version_id", "target_version_id"}:
                raise ValidationError(f"invalid conflict entry at index {index}")
            source_id, target_id = row["source_version_id"], row["target_version_id"]
            if (
                not isinstance(source_id, str) or not source_id
                or not isinstance(target_id, str) or not target_id
            ):
                raise ValidationError(f"invalid conflict identity at index {index}")
            source, target = version_rows.get(source_id), version_rows.get(target_id)
            if source is None or target is None or source.record_id != target.record_id:
                raise ValidationError(f"invalid conflict endpoints at index {index}")
            pair = (source_id, target_id)
            if pair in seen_conflicts:
                raise ValidationError(f"duplicate conflict annotation at index {index}")
            store._conflicts.append(Conflict(*pair))
            seen_conflicts.add(pair)
        return store

    def _new_id(self, occupied: set[str]) -> str:
        while True:
            candidate = str(uuid4())
            if candidate not in occupied:
                return candidate

    def create_record(self, content: Any, metadata: Any = None) -> Version:
        content_copy = _snapshot(content, "content")
        metadata_copy = None if metadata is None else _snapshot(metadata, "metadata")
        record_id = self._new_id(set(self._records))
        version_id = self._new_id(set(self._versions))
        version = Version(
            version_id, record_id, (),
            json.dumps(content_copy, allow_nan=False, separators=(",", ":")),
            None if metadata_copy is None else json.dumps(metadata_copy, allow_nan=False, separators=(",", ":")),
        )
        self._versions[version_id] = version
        self._records[record_id] = [version_id]
        return version

    def revise(self, record_id: str, content: Any, *, parent_version_ids: list[str] | tuple[str, ...] | None = None, metadata: Any = None) -> Version:
        if record_id not in self._records:
            raise NotFoundError(f"record not found: {record_id}")
        content_copy = _snapshot(content, "content")
        metadata_copy = None if metadata is None else _snapshot(metadata, "metadata")
        parents = tuple(dict.fromkeys(parent_version_ids if parent_version_ids is not None else self.tips(record_id)))
        if not parents:
            raise RecordError("a revision must have at least one parent")
        for parent_id in parents:
            parent = self.get_version(parent_id)
            if parent.record_id != record_id:
                raise RecordError(f"parent {parent_id} belongs to another record")
        version_id = self._new_id(set(self._versions))
        version = Version(
            version_id, record_id, parents,
            json.dumps(content_copy, allow_nan=False, separators=(",", ":")),
            None if metadata_copy is None else json.dumps(metadata_copy, allow_nan=False, separators=(",", ":")),
        )
        self._versions[version_id] = version
        self._records[record_id].append(version_id)
        return version

    def get_version(self, version_id: str) -> Version:
        try:
            return self._versions[version_id]
        except KeyError as exc:
            raise NotFoundError(f"version not found: {version_id}") from exc

    def versions(self, record_id: str) -> tuple[Version, ...]:
        if record_id not in self._records:
            raise NotFoundError(f"record not found: {record_id}")
        return tuple(self._versions[version_id] for version_id in self._records[record_id])

    def tips(self, record_id: str) -> tuple[str, ...]:
        versions = self.versions(record_id)
        parent_ids = {parent for version in versions for parent in version.parents}
        return tuple(version.version_id for version in versions if version.version_id not in parent_ids)

    def annotate_conflict(self, source_version_id: str, target_version_id: str) -> Conflict:
        source = self.get_version(source_version_id)
        target = self.get_version(target_version_id)
        if source.record_id != target.record_id:
            raise RecordError("conflict endpoints must belong to the same record")
        conflict = Conflict(source_version_id, target_version_id)
        if conflict not in self._conflicts:
            self._conflicts.append(conflict)
        return conflict

    def conflicts(self, record_id: str | None = None) -> tuple[Conflict, ...]:
        if record_id is None:
            return tuple(self._conflicts)
        if record_id not in self._records:
            raise NotFoundError(f"record not found: {record_id}")
        ids = set(self._records[record_id])
        return tuple(c for c in self._conflicts if c.source_version_id in ids and c.target_version_id in ids)
