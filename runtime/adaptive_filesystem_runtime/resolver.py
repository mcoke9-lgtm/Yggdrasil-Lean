"""Pure, read-only review-history resolver.

The caller supplies a snapshot. Parent links represent the global review-history
chain; branch_id + sequence represents branch-local order; source branch + anchor
represents fork origin; revision_of_id independently identifies a revision target.
Blank references remain unclassified; they are not promoted to explicit N/A.
"""
from __future__ import annotations
from dataclasses import dataclass
from typing import Iterable


@dataclass(frozen=True)
class Record:
    record_id: str
    branch_id: str
    sequence: int
    parent_record_id: str | None = None
    source_branch_id: str | None = None
    source_anchor_id: str | None = None
    revision_of_id: str | None = None


@dataclass(frozen=True)
class ResolutionResult:
    record_id: str
    parent_record_id: str | None
    source_anchor_id: str | None
    revision_of_id: str | None
    branch_predecessor_id: str | None
    parent_reference_state: str
    source_anchor_reference_state: str
    revision_reference_state: str
    branch_predecessor_state: str


class ResolutionError(ValueError):
    """Structured resolver failure with a stable machine-readable code."""
    def __init__(self, code: str, detail: str):
        self.code = code
        self.detail = detail
        super().__init__(f"{code}: {detail}")


def _index(records: Iterable[Record]) -> dict[str, Record]:
    items = list(records)
    if any(not isinstance(r, Record) for r in items):
        raise ResolutionError("MALFORMED_RECORD", "every input item must be a Record")
    for record in items:
        if not record.record_id or not record.branch_id:
            raise ResolutionError("MALFORMED_RECORD", "record_id and branch_id are required")
        if not isinstance(record.sequence, int) or isinstance(record.sequence, bool) or record.sequence < 0:
            raise ResolutionError("MALFORMED_RECORD", f"invalid sequence for {record.record_id}")
        if bool(record.source_branch_id) != bool(record.source_anchor_id):
            raise ResolutionError(
                "MALFORMED_SOURCE_REFERENCE",
                f"{record.record_id} must provide both source_branch_id and source_anchor_id",
            )
    ids = [r.record_id for r in items]
    from collections import Counter
    duplicates = sorted(record_id for record_id, count in Counter(ids).items() if count > 1)
    if duplicates:
        raise ResolutionError("DUPLICATE_RECORD_ID", ", ".join(duplicates))
    seen_sequences: set[tuple[str, int]] = set()
    for record in items:
        key = (record.branch_id, record.sequence)
        if key in seen_sequences:
            raise ResolutionError("DUPLICATE_BRANCH_SEQUENCE", f"{record.branch_id} sequence {record.sequence}")
        seen_sequences.add(key)
    return {r.record_id: r for r in items}


def _resolve_reference(index: dict[str, Record], ref_id: str | None, code: str, owner: str) -> Record | None:
    if ref_id is None or ref_id == "":
        return None
    target = index.get(ref_id)
    if target is None:
        raise ResolutionError(code, f"{owner} references missing record {ref_id}")
    return target


def _check_parent_cycles(index: dict[str, Record]) -> None:
    state: dict[str, int] = {}
    for start in index:
        if state.get(start, 0) == 2:
            continue
        path: list[str] = []
        current: str | None = start
        while current is not None:
            mark = state.get(current, 0)
            if mark == 1:
                raise ResolutionError("PARENT_CYCLE", " -> ".join(path + [current]))
            if mark == 2:
                break
            state[current] = 1
            path.append(current)
            record = index[current]
            parent = _resolve_reference(index, record.parent_record_id, "MISSING_PARENT", current)
            current = parent.record_id if parent else None
        for record_id in path:
            state[record_id] = 2


def resolve_history(records: Iterable[Record], record_id: str) -> ResolutionResult:
    """Resolve one record against a caller-supplied snapshot.

    A blank optional reference yields state blank_unclassified; this schema cannot
    distinguish an intentional root/N/A from an omitted or unknown value. A missing
    branch predecessor means only that none was present in this snapshot. Parent
    references across the supplied snapshot are checked for missing links and cycles.
    """
    index = _index(records)
    target = index.get(record_id)
    if target is None:
        raise ResolutionError("MISSING_RECORD", f"record {record_id} is absent from snapshot")
    _check_parent_cycles(index)
    parent = _resolve_reference(index, target.parent_record_id, "MISSING_PARENT", target.record_id)
    source_anchor = _resolve_reference(index, target.source_anchor_id, "MISSING_SOURCE_ANCHOR", target.record_id)
    revision = _resolve_reference(index, target.revision_of_id, "MISSING_REVISION_TARGET", target.record_id)
    if source_anchor is not None and source_anchor.branch_id != target.source_branch_id:
        raise ResolutionError(
            "SOURCE_BRANCH_MISMATCH",
            f"{target.record_id} declares source branch {target.source_branch_id}, "
            f"but anchor {source_anchor.record_id} belongs to {source_anchor.branch_id}",
        )
    predecessors = [
        r for r in index.values()
        if r.branch_id == target.branch_id and r.sequence < target.sequence
    ]
    branch_predecessor = max(predecessors, key=lambda r: r.sequence, default=None)
    return ResolutionResult(
        record_id=target.record_id,
        parent_record_id=parent.record_id if parent else None,
        source_anchor_id=source_anchor.record_id if source_anchor else None,
        revision_of_id=revision.record_id if revision else None,
        branch_predecessor_id=branch_predecessor.record_id if branch_predecessor else None,
        parent_reference_state="linked" if parent else "blank_unclassified",
        source_anchor_reference_state="linked" if source_anchor else "blank_unclassified",
        revision_reference_state="linked" if revision else "blank_unclassified",
        branch_predecessor_state="found_in_snapshot" if branch_predecessor else "none_found_in_snapshot",
    )
