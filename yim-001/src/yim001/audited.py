"""Transactional memory operations coupled to a provenance event ledger.

The wrapper keeps the underlying MemoryStore private and commits a mutation
only after its corresponding event has been successfully constructed.
"""
from __future__ import annotations

from pathlib import Path
from typing import Any

from .core import MemoryStore
from .provenance import EventLedger
from .storage import read_json_strict, write_json_atomic


class AuditedMemoryStore:
    """MemoryStore facade that records each supported mutation atomically."""

    FORMAT = "YIM-001-Audited-Memory-System"
    FORMAT_VERSION = 1

    def __init__(
        self,
        actor_id: str,
        *,
        store: MemoryStore | None = None,
        ledger: EventLedger | None = None,
    ) -> None:
        if not isinstance(actor_id, str) or not actor_id.strip():
            raise ValueError("actor_id must be a non-empty caller-supplied identifier")
        self.actor_id = actor_id
        self.__store = store if store is not None else MemoryStore()
        self.__ledger = ledger if ledger is not None else EventLedger()

    @property
    def events(self):
        return self.__ledger.events

    def get_version(self, version_id: str):
        return self.__store.get_version(version_id)

    def versions(self, record_id: str):
        return self.__store.versions(record_id)

    def tips(self, record_id: str):
        return self.__store.tips(record_id)

    def conflicts(self, record_id: str | None = None):
        return self.__store.conflicts(record_id)

    def _transact(self, event_type: str, operation, *, details=None, timestamp=None):
        # Work on detached copies. Neither live object changes unless both the
        # memory operation and its provenance append succeed.
        candidate_store = MemoryStore.from_snapshot(self.__store.to_snapshot())
        candidate_ledger = EventLedger.from_snapshot(self.__ledger.to_snapshot())
        result = operation(candidate_store)
        record_id = getattr(result, "record_id", None)
        version_id = getattr(result, "version_id", None)
        if event_type == "conflict.annotated":
            details = {
                "source_version_id": result.source_version_id,
                "target_version_id": result.target_version_id,
            }
            record_id = candidate_store.get_version(result.source_version_id).record_id
            version_id = result.target_version_id
        candidate_ledger.append(
            event_type,
            self.actor_id,
            record_id=record_id,
            version_id=version_id,
            details={} if details is None else details,
            timestamp=timestamp,
        )
        self.__store = candidate_store
        self.__ledger = candidate_ledger
        return result

    def create_record(self, content: Any, metadata: Any = None, *, timestamp=None):
        return self._transact(
            "record.created",
            lambda store: store.create_record(content, metadata),
            details={"operation": "create"},
            timestamp=timestamp,
        )

    def revise(
        self,
        record_id: str,
        content: Any,
        *,
        parent_version_ids=None,
        metadata: Any = None,
        timestamp=None,
    ):
        return self._transact(
            "record.revised",
            lambda store: store.revise(
                record_id,
                content,
                parent_version_ids=parent_version_ids,
                metadata=metadata,
            ),
            details={
                "operation": "revise",
                "parent_version_ids": list(parent_version_ids) if parent_version_ids is not None else None,
            },
            timestamp=timestamp,
        )

    def annotate_conflict(self, source_version_id: str, target_version_id: str, *, timestamp=None):
        return self._transact(
            "conflict.annotated",
            lambda store: store.annotate_conflict(source_version_id, target_version_id),
            timestamp=timestamp,
        )

    def to_snapshot(self) -> dict[str, Any]:
        return {
            "format": self.FORMAT,
            "format_version": self.FORMAT_VERSION,
            "actor_id": self.actor_id,
            "memory": self.__store.to_snapshot(),
            "provenance": self.__ledger.to_snapshot(),
        }

    @classmethod
    def from_snapshot(cls, snapshot: Any) -> "AuditedMemoryStore":
        required = {"format", "format_version", "actor_id", "memory", "provenance"}
        if not isinstance(snapshot, dict) or set(snapshot) != required:
            raise ValueError("invalid audited snapshot structure")
        if snapshot["format"] != cls.FORMAT or type(snapshot["format_version"]) is not int or snapshot["format_version"] != cls.FORMAT_VERSION:
            raise ValueError("unsupported audited snapshot format or version")
        actor_id = snapshot["actor_id"]
        store = MemoryStore.from_snapshot(snapshot["memory"])
        ledger = EventLedger.from_snapshot(snapshot["provenance"])
        return cls(actor_id, store=store, ledger=ledger)


def save_audited(store: AuditedMemoryStore, path: str | Path) -> None:
    """Atomically persist memory and provenance in one combined JSON file."""
    if not isinstance(store, AuditedMemoryStore):
        raise TypeError("store must be an AuditedMemoryStore")
    write_json_atomic(path, store.to_snapshot())


def load_audited(path: str | Path) -> AuditedMemoryStore:
    """Load and validate a combined audited snapshot."""
    return AuditedMemoryStore.from_snapshot(read_json_strict(path))
