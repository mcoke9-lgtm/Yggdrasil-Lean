"""Logical Living Tree placement, independent of physical file paths.

This module is an additive index over stable memory record identities. It does
not move or duplicate memory content. Nodes may be nested recursively; a record
has exactly one primary placement and may have additional contextual placements.
"""
from __future__ import annotations

from dataclasses import dataclass
from typing import Any
import unicodedata


PARTS = frozenset({"roots", "trunk", "branches", "leaves"})


class TreeIndexError(ValueError):
    """Raised when a logical Tree index or placement is invalid."""


@dataclass(frozen=True)
class TreeNode:
    node_id: str
    part: str
    label: str
    description: str
    parent_id: str | None = None


@dataclass(frozen=True)
class RecordPlacement:
    record_id: str
    node_id: str
    role: str  # "primary" or "contextual"


class LivingTreeIndex:
    """A recursive logical map from memory records to meaningful Tree locations."""

    FORMAT = "Living-Tree-Logical-Index"
    FORMAT_VERSION = 1

    def __init__(self) -> None:
        self._nodes: dict[str, TreeNode] = {}
        self._placements: list[RecordPlacement] = []

    @property
    def nodes(self) -> tuple[TreeNode, ...]:
        return tuple(self._nodes.values())

    @property
    def placements(self) -> tuple[RecordPlacement, ...]:
        return tuple(self._placements)

    def add_node(
        self,
        node_id: str,
        part: str,
        label: str,
        description: str = "",
        parent_id: str | None = None,
    ) -> TreeNode:
        if not isinstance(node_id, str) or not node_id.strip():
            raise TreeIndexError("node_id must be a non-empty string")
        if node_id in self._nodes:
            raise TreeIndexError(f"duplicate Tree node: {node_id}")
        if not isinstance(part, str) or part not in PARTS:
            raise TreeIndexError(f"part must be one of: {', '.join(sorted(PARTS))}")
        if not isinstance(label, str) or not label.strip():
            raise TreeIndexError("label must be a non-empty string")
        if not isinstance(description, str):
            raise TreeIndexError("description must be a string")
        if parent_id is not None and parent_id not in self._nodes:
            raise TreeIndexError(f"parent Tree node not found: {parent_id}")
        node = TreeNode(node_id, part, label.strip(), description, parent_id)
        self._nodes[node_id] = node
        return node

    def place_record(self, record_id: str, node_id: str, role: str = "primary") -> RecordPlacement:
        if not isinstance(record_id, str) or not record_id.strip():
            raise TreeIndexError("record_id must be a non-empty string")
        if node_id not in self._nodes:
            raise TreeIndexError(f"Tree node not found: {node_id}")
        if role not in {"primary", "contextual"}:
            raise TreeIndexError("placement role must be 'primary' or 'contextual'")
        existing = [p for p in self._placements if p.record_id == record_id]
        if any(p.node_id == node_id for p in existing):
            raise TreeIndexError("record is already placed at this Tree node")
        if role == "primary" and any(p.role == "primary" for p in existing):
            raise TreeIndexError(f"record already has a primary placement: {record_id}")
        placement = RecordPlacement(record_id, node_id, role)
        self._placements.append(placement)
        return placement

    def placements_for(self, record_id: str) -> tuple[RecordPlacement, ...]:
        return tuple(p for p in self._placements if p.record_id == record_id)

    def primary_location(self, record_id: str) -> TreeNode:
        primary = [p for p in self._placements if p.record_id == record_id and p.role == "primary"]
        if len(primary) != 1:
            raise TreeIndexError(
                f"record must have exactly one primary placement; found {len(primary)}: {record_id}"
            )
        return self._nodes[primary[0].node_id]

    def find_nodes(self, phrase: str) -> tuple[TreeNode, ...]:
        """Find logical locations by label/description, not storage path or filename."""
        if not isinstance(phrase, str) or not phrase.strip():
            raise TreeIndexError("search phrase must be a non-empty string")
        needle = _normalise(phrase)
        return tuple(
            node for node in self._nodes.values()
            if needle in _normalise(node.label + " " + node.description)
        )

    def records_at(self, node_id: str, include_descendants: bool = True) -> tuple[str, ...]:
        if node_id not in self._nodes:
            raise TreeIndexError(f"Tree node not found: {node_id}")
        accepted = {node_id}
        if include_descendants:
            changed = True
            while changed:
                changed = False
                for node in self._nodes.values():
                    if node.parent_id in accepted and node.node_id not in accepted:
                        accepted.add(node.node_id)
                        changed = True
        return tuple(dict.fromkeys(
            p.record_id for p in self._placements if p.node_id in accepted
        ))

    def to_snapshot(self) -> dict[str, Any]:
        return {
            "format": self.FORMAT,
            "format_version": self.FORMAT_VERSION,
            "nodes": [
                {"node_id": n.node_id, "part": n.part, "label": n.label,
                 "description": n.description, "parent_id": n.parent_id}
                for n in self._nodes.values()
            ],
            "placements": [
                {"record_id": p.record_id, "node_id": p.node_id, "role": p.role}
                for p in self._placements
            ],
        }

    @classmethod
    def from_snapshot(cls, snapshot: Any) -> "LivingTreeIndex":
        required = {"format", "format_version", "nodes", "placements"}
        if not isinstance(snapshot, dict) or set(snapshot) != required:
            raise TreeIndexError("invalid Living Tree index structure")
        if snapshot["format"] != cls.FORMAT or type(snapshot["format_version"]) is not int or snapshot["format_version"] != cls.FORMAT_VERSION:
            raise TreeIndexError("unsupported Living Tree index format or version")
        if not isinstance(snapshot["nodes"], list) or not isinstance(snapshot["placements"], list):
            raise TreeIndexError("nodes and placements must be arrays")
        index = cls()
        pending = list(snapshot["nodes"])
        while pending:
            remaining = []
            progressed = False
            for row in pending:
                if not isinstance(row, dict) or set(row) != {"node_id", "part", "label", "description", "parent_id"}:
                    raise TreeIndexError("invalid Tree node entry")
                parent = row["parent_id"]
                if parent is not None and parent not in index._nodes:
                    remaining.append(row)
                    continue
                index.add_node(row["node_id"], row["part"], row["label"], row["description"], parent)
                progressed = True
            if not progressed:
                raise TreeIndexError("Tree nodes contain missing parents or a cycle")
            pending = remaining
        for row in snapshot["placements"]:
            if not isinstance(row, dict) or set(row) != {"record_id", "node_id", "role"}:
                raise TreeIndexError("invalid record placement entry")
            index.place_record(row["record_id"], row["node_id"], row["role"])
        return index


def _normalise(value: str) -> str:
    return " ".join(unicodedata.normalize("NFKC", value).casefold().split())
