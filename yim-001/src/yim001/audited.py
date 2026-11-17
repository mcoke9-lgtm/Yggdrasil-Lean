"""Transactional memory operations coupled to provenance and logical Tree placement."""
from __future__ import annotations

from pathlib import Path
from typing import Any

from .core import MemoryStore
from .living_tree import LivingTreeIndex
from .provenance import EventLedger
from .storage import read_json_strict, write_json_atomic


class AuditedMemoryStore:
    """Memory facade with append-only provenance and a persisted logical Tree index."""

    FORMAT = "YIM-001-Audited-Memory-System"
    FORMAT_VERSION = 2

    def __init__(
        self,
        actor_id: str,
        *,
        store: MemoryStore | None = None,
        ledger: EventLedger | None = None,
        living_tree: LivingTreeIndex | None = None,
    ) -> None:
        if not isinstance(actor_id, str) or not actor_id.strip():
            raise ValueError("actor_id must be a non-empty caller-supplied identifier")
        self.actor_id = actor_id
        self.__store = store if store is not None else MemoryStore()
        self.__ledger = ledger if ledger is not None else EventLedger()
        self.__living_tree = living_tree if living_tree is not None else LivingTreeIndex()

    @property
    def events(self):
        return self.__ledger.events

    @property
    def living_tree(self) -> LivingTreeIndex:
        """Return the logical index; callers should mutate it through this facade."""
        return LivingTreeIndex.from_snapshot(self.__living_tree.to_snapshot())

    def tree_nodes(self):
        return self.__living_tree.nodes

    def placements_for(self, record_id: str):
        return self.__living_tree.placements_for(record_id)

    def primary_location(self, record_id: str):
        return self.__living_tree.primary_location(record_id)

    def find_tree_locations(self, phrase: str):
        return self.__living_tree.find_nodes(phrase)

    def records_at_tree_location(self, node_id: str, include_descendants: bool = True):
        return self.__living_tree.records_at(node_id, include_descendants)

    def add_tree_location(self, node_id: str, part: str, label: str,
                          description: str = "", parent_id: str | None = None):
        candidate = LivingTreeIndex.from_snapshot(self.__living_tree.to_snapshot())
        node = candidate.add_node(node_id, part, label, description, parent_id)
        self.__living_tree = candidate
        return node

    def place_record(self, record_id: str, node_id: str, role: str = "primary", *, timestamp=None):
        # Validate record identity before changing the index.
        self.__store.versions(record_id)
        candidate_tree = LivingTreeIndex.from_snapshot(self.__living_tree.to_snapshot())
        placement = candidate_tree.place_record(record_id, node_id, role)
        candidate_ledger = EventLedger.from_snapshot(self.__ledger.to_snapshot())
        candidate_ledger.append(
            "record.tree_placed", self.actor_id, record_id=record_id,
            details={"node_id": node_id, "role": role}, timestamp=timestamp,
        )
        self.__living_tree = candidate_tree
        self.__ledger = candidate_ledger
        return placement

    def _transact(self, event_type: str, operation, *, details=None, timestamp=None):
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
            event_type, self.actor_id, record_id=record_id, version_id=version_id,
            details={} if details is None else details, timestamp=timestamp,
        )
        self.__store = candidate_store
        self.__ledger = candidate_ledger
        return result

    def create_record(self, content: Any, metadata: Any = None, *, timestamp=None):
        return self._transact(
            "record.created", lambda store: store.create_record(content, metadata),
            details={"operation": "create"}, timestamp=timestamp,
        )

    def revise(self, record_id: str, content: Any, *, parent_version_ids=None,
               metadata: Any = None, timestamp=None):
        return self._transact(
            "record.revised",
            lambda store: store.revise(record_id, content,
                parent_version_ids=parent_version_ids, metadata=metadata),
            details={"operation": "revise",
                     "parent_version_ids": list(parent_version_ids) if parent_version_ids is not None else None},
            timestamp=timestamp,
        )

    def annotate_conflict(self, source_version_id: str, target_version_id: str, *, timestamp=None):
        return self._transact(
            "conflict.annotated",
            lambda store: store.annotate_conflict(source_version_id, target_version_id),
            timestamp=timestamp,
        )

    def _adopt(self, candidate: "AuditedMemoryStore") -> None:
        if not isinstance(candidate, AuditedMemoryStore):
            raise TypeError("candidate must be an AuditedMemoryStore")
        self.__store = candidate.__store
        self.__ledger = candidate.__ledger
        self.__living_tree = candidate.__living_tree

    def to_snapshot(self) -> dict[str, Any]:
        return {
            "format": self.FORMAT,
            "format_version": self.FORMAT_VERSION,
            "actor_id": self.actor_id,
            "memory": self.__store.to_snapshot(),
            "provenance": self.__ledger.to_snapshot(),
            "living_tree": self.__living_tree.to_snapshot(),
        }

    @classmethod
    def from_snapshot(cls, snapshot: Any) -> "AuditedMemoryStore":
        required = {"format", "format_version", "actor_id", "memory", "provenance"}
        if not isinstance(snapshot, dict) or not required.issubset(snapshot) or set(snapshot) - (required | {"living_tree"}):
            raise ValueError("invalid audited snapshot structure")
        if snapshot["format"] != cls.FORMAT or type(snapshot["format_version"]) is not int or snapshot["format_version"] not in {1, 2}:
            raise ValueError("unsupported audited snapshot format or version")
        if snapshot["format_version"] == 2 and "living_tree" not in snapshot:
            raise ValueError("version 2 audited snapshot requires living_tree")
        actor_id = snapshot["actor_id"]
        store = MemoryStore.from_snapshot(snapshot["memory"])
        ledger = EventLedger.from_snapshot(snapshot["provenance"])
        tree = LivingTreeIndex.from_snapshot(snapshot["living_tree"]) if "living_tree" in snapshot else LivingTreeIndex()
        return cls(actor_id, store=store, ledger=ledger, living_tree=tree)


def save_audited(store: AuditedMemoryStore, path: str | Path) -> None:
    """Atomically persist memory, provenance, and logical Tree index together."""
    if not isinstance(store, AuditedMemoryStore):
        raise TypeError("store must be an AuditedMemoryStore")
    write_json_atomic(path, store.to_snapshot())


def load_audited(path: str | Path) -> AuditedMemoryStore:
    """Load and validate a combined audited snapshot."""
    return AuditedMemoryStore.from_snapshot(read_json_strict(path))
