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

    def __init__(self) -> None:
        self._versions: dict[str, Version] = {}
        self._records: dict[str, list[str]] = {}
        self._conflicts: list[Conflict] = []

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
