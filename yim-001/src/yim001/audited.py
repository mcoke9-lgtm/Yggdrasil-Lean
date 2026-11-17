"""Transactional memory operations coupled to provenance and logical Tree placement."""
from __future__ import annotations

from pathlib import Path
from typing import Any

from .core import MemoryStore, NotFoundError
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

    def get_version(self, version_id: str):
        return self.__store.get_version(version_id)

    def versions(self, record_id: str):
        return self.__store.versions(record_id)

    def tips(self, record_id: str):
        return self.__store.tips(record_id)

    def conflicts(self, record_id: str | None = None):
        return self.__store.conflicts(record_id)

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

    def capture_work(
        self,
        content: Any,
        *,
        primary_node_id: str,
        source_kind: str,
        language: str,
        source_reference: str | None = None,
        contextual_node_ids: list[str] | None = None,
        metadata: Any = None,
        timestamp=None,
        intake_identity: dict[str, str] | None = None,
    ):
        """Atomically capture supplied work, preserve its language/source, and place it in the Tree.

        This is an explicit local intake API. It does not read chat history or
        automatically discover source material.
        """
        if not isinstance(primary_node_id, str) or not primary_node_id.strip():
            raise ValueError("primary_node_id must be a non-empty logical Tree node")
        if not isinstance(source_kind, str) or not source_kind.strip():
            raise ValueError("source_kind must be a non-empty description")
        if not isinstance(language, str) or not language.strip():
            raise ValueError("language must be supplied or explicitly marked undetermined")
        if source_reference is not None and (
            not isinstance(source_reference, str) or not source_reference.strip()
        ):
            raise ValueError("source_reference must be null or a non-empty reference")
        if contextual_node_ids is None:
            contextual_node_ids = []
        if not isinstance(contextual_node_ids, list) or not all(
            isinstance(item, str) and item.strip() for item in contextual_node_ids
        ):
            raise ValueError("contextual_node_ids must be a list of non-empty Tree node IDs")
        if primary_node_id in contextual_node_ids or len(set(contextual_node_ids)) != len(contextual_node_ids):
            raise ValueError("contextual locations must be unique and differ from the primary location")
        if intake_identity is not None:
            if not isinstance(intake_identity, dict) or set(intake_identity) != {"queue_id", "packet_id"}:
                raise ValueError("intake_identity must contain exactly queue_id and packet_id")
            if not all(isinstance(value, str) and value.strip() for value in intake_identity.values()):
                raise ValueError("intake queue and packet identities must be non-empty strings")
        capture_metadata = {
            "capture": {
                "source_kind": source_kind,
                "source_reference": source_reference,
                "language": language,
                "primary_logical_location": primary_node_id,
                "contextual_logical_locations": list(contextual_node_ids),
                "source_content_preserved_as_submitted": True,
                "intake_identity": intake_identity,
            }
        }
        if metadata is not None:
            capture_metadata["user_metadata"] = metadata
        candidate_store = MemoryStore.from_snapshot(self.__store.to_snapshot())
        candidate_ledger = EventLedger.from_snapshot(self.__ledger.to_snapshot())
        candidate_tree = LivingTreeIndex.from_snapshot(self.__living_tree.to_snapshot())
        # Validate all logical locations before committing any part of the capture.
        if primary_node_id not in {node.node_id for node in candidate_tree.nodes}:
            raise ValueError(f"primary Tree location not found: {primary_node_id}")
        for node_id in contextual_node_ids:
            if node_id not in {node.node_id for node in candidate_tree.nodes}:
                raise ValueError(f"contextual Tree location not found: {node_id}")
        version = candidate_store.create_record(content, capture_metadata)
        candidate_tree.place_record(version.record_id, primary_node_id, "primary")
        for node_id in contextual_node_ids:
            candidate_tree.place_record(version.record_id, node_id, "contextual")
        candidate_ledger.append(
            "work.captured", self.actor_id,
            record_id=version.record_id, version_id=version.version_id,
            details={
                "source_kind": source_kind,
                "source_reference": source_reference,
                "language": language,
                "primary_logical_location": primary_node_id,
                "contextual_logical_locations": list(contextual_node_ids),
                "source_content_preserved_as_submitted": True,
                "intake_identity": intake_identity,
            },
            timestamp=timestamp,
        )
        self.__store = candidate_store
        self.__ledger = candidate_ledger
        self.__living_tree = candidate_tree
        return version

    def import_intake_packet(
        self, packet: dict[str, Any], *, queue_id: str,
        primary_node_id: str, contextual_node_ids: list[str] | None = None,
    ):
        """Import one pending packet; repeated calls return the original capture."""
        required = {
            "packet_id", "captured_at", "source_kind", "source_reference",
            "language", "content", "metadata", "placement_status",
        }
        if not isinstance(packet, dict) or not required.issubset(packet):
            raise ValueError("invalid intake packet")
        if packet["placement_status"] != "awaiting_placement":
            raise ValueError("only packets awaiting placement can be imported")
        if not isinstance(queue_id, str) or not queue_id.strip():
            raise ValueError("queue_id must be a non-empty identifier")
        identity = {"queue_id": queue_id, "packet_id": packet["packet_id"]}
        for event in self.__ledger.events:
            if event.event_type == "work.captured" and event.details.get("intake_identity") == identity:
                if event.record_id is None or event.version_id is None:
                    raise ValueError("existing intake capture event lacks record/version identity")
                version = self.__store.get_version(event.version_id)
                if version.record_id != event.record_id:
                    raise ValueError("existing intake capture provenance identity is inconsistent")
                expected_contextual = [] if contextual_node_ids is None else list(contextual_node_ids)
                expected_capture = {
                    "source_kind": packet["source_kind"],
                    "source_reference": packet["source_reference"],
                    "language": packet["language"],
                    "primary_logical_location": primary_node_id,
                    "contextual_logical_locations": expected_contextual,
                    "source_content_preserved_as_submitted": True,
                    "intake_identity": identity,
                }
                stored_metadata = version.metadata
                stored_capture = stored_metadata.get("capture") if isinstance(stored_metadata, dict) else None
                expected_metadata = {"capture": expected_capture}
                if packet["metadata"] is not None:
                    expected_metadata["user_metadata"] = packet["metadata"]
                actual_placements = self.__living_tree.placements_for(version.record_id)
                actual_primary = [item.node_id for item in actual_placements if item.role == "primary"]
                actual_contextual = [item.node_id for item in actual_placements if item.role == "contextual"]
                if (
                    version.content != packet["content"]
                    or stored_metadata != expected_metadata
                    or actual_primary != [primary_node_id]
                    or actual_contextual != expected_contextual
                ):
                    raise ValueError(
                        "existing intake capture differs from packet content, metadata, or requested Tree placement"
                    )
                return version
        return self.capture_work(
            packet["content"],
            primary_node_id=primary_node_id,
            contextual_node_ids=contextual_node_ids,
            source_kind=packet["source_kind"],
            source_reference=packet["source_reference"],
            language=packet["language"],
            metadata=packet["metadata"],
            timestamp=packet["captured_at"],
            intake_identity=identity,
        )

    def record_presence(self, record_id: str):
        """Report presence only within this loaded store; absence is not deletion."""
        placements = self.__living_tree.placements_for(record_id)
        try:
            versions = self.__store.versions(record_id)
        except NotFoundError:
            return {
                "status": "not_found_in_loaded_store",
                "record_id": record_id,
                "tree_placements": [
                    {"node_id": item.node_id, "role": item.role} for item in placements
                ],
                "interpretation": "This store does not contain the record. This does not establish deletion, nonexistence, or loss elsewhere.",
            }
        return {
            "status": "present_in_loaded_store",
            "record_id": record_id,
            "version_ids": [item.version_id for item in versions],
            "tree_placements": [
                {"node_id": item.node_id, "role": item.role} for item in placements
            ],
        }

    def record_recovery(self, source_instance_id: str, backup_sha256: str, *, timestamp=None):
        """Append evidence that a validated backup snapshot was restored."""
        if not isinstance(source_instance_id, str) or not source_instance_id.strip():
            raise ValueError("source_instance_id must be a non-empty identifier")
        if not isinstance(backup_sha256, str) or len(backup_sha256) != 64 or any(
            char not in "0123456789abcdef" for char in backup_sha256
        ):
            raise ValueError("backup_sha256 must be a lowercase SHA-256 digest")
        candidate = EventLedger.from_snapshot(self.__ledger.to_snapshot())
        event = candidate.append(
            "snapshot.recovered", self.actor_id,
            details={
                "source_instance_id": source_instance_id,
                "backup_sha256": backup_sha256,
                "recovery_kind": "restored_backup_snapshot",
                "original_record_identities_preserved": True,
                "reconstruction": False,
            },
            timestamp=timestamp,
        )
        self.__ledger = candidate
        return event

    def transform_record(self, source_record_id: str, source_version_id: str,
                         kind: str, content: Any, *, description: str,
                         evidence_refs: list[str] | None = None,
                         metadata: Any = None, timestamp=None):
        """Create a distinct derived record while preserving its source unchanged."""
        allowed = {"copy", "derivative", "translation", "reconstruction"}
        if kind not in allowed:
            raise ValueError(f"kind must be one of {sorted(allowed)}")
        if not isinstance(description, str) or not description.strip():
            raise ValueError("description must explain the transformation")
        if evidence_refs is None:
            evidence_refs = []
        if not isinstance(evidence_refs, list) or not all(
            isinstance(item, str) and item.strip() for item in evidence_refs
        ):
            raise ValueError("evidence_refs must be a list of non-empty identifiers")
        source = self.__store.get_version(source_version_id)
        if source.record_id != source_record_id:
            raise ValueError("source version does not belong to source record")
        candidate_store = MemoryStore.from_snapshot(self.__store.to_snapshot())
        candidate_ledger = EventLedger.from_snapshot(self.__ledger.to_snapshot())
        derived = candidate_store.create_record(content, metadata)
        candidate_ledger.append(
            "record.transformed", self.actor_id, record_id=derived.record_id,
            version_id=derived.version_id,
            details={
                "source_record_id": source_record_id,
                "source_version_id": source_version_id,
                "transformation_kind": kind,
                "description": description,
                "evidence_refs": list(evidence_refs),
                "identity_relation": "reconstruction_not_original" if kind == "reconstruction" else "distinct_derived_record",
            }, timestamp=timestamp,
        )
        self.__store = candidate_store
        self.__ledger = candidate_ledger
        return derived

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
        valid_record_ids = {row["record_id"] for row in snapshot["memory"]["records"]}
        orphaned = sorted({placement.record_id for placement in tree.placements} - valid_record_ids)
        if orphaned:
            raise ValueError(f"Living Tree placements reference missing memory records: {orphaned}")
        return cls(actor_id, store=store, ledger=ledger, living_tree=tree)


def save_audited(store: AuditedMemoryStore, path: str | Path) -> None:
    """Atomically persist memory, provenance, and logical Tree index together."""
    if not isinstance(store, AuditedMemoryStore):
        raise TypeError("store must be an AuditedMemoryStore")
    write_json_atomic(path, store.to_snapshot())


def load_audited(path: str | Path) -> AuditedMemoryStore:
    """Load and validate a combined audited snapshot."""
    return AuditedMemoryStore.from_snapshot(read_json_strict(path))
