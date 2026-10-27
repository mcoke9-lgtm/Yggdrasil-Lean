"""Snapshot-wide relationship audit; read-only and inference-free."""
from __future__ import annotations

from dataclasses import dataclass
from typing import Iterable

from .resolver import Record, ResolutionError, _index


@dataclass(frozen=True)
class SnapshotFinding:
    record_id: str | None
    relationship: str
    code: str
    detail: str
    severity: str  # error or info


@dataclass(frozen=True)
class SnapshotAudit:
    record_count: int
    findings: tuple[SnapshotFinding, ...]

    @property
    def status(self) -> str:
        return "failed" if any(f.severity == "error" for f in self.findings) else "passed"

    @property
    def unclassified_count(self) -> int:
        return sum(f.code == "BLANK_UNCLASSIFIED" for f in self.findings)

    @property
    def error_count(self) -> int:
        return sum(f.severity == "error" for f in self.findings)


def audit_snapshot(records: Iterable[Record]) -> SnapshotAudit:
    """Check every record relationship without repairing or inferring blanks.

    Structural index failures return a single snapshot-level error. Blank optional
    relationships are reported as informational findings, not treated as failures.
    Parent cycles and missing references are reported for all affected records.
    """
    items = list(records)
    try:
        index = _index(items)
    except ResolutionError as exc:
        return SnapshotAudit(
            record_count=len(items),
            findings=(SnapshotFinding(None, "snapshot", exc.code, exc.detail, "error"),),
        )

    findings: list[SnapshotFinding] = []
    for record in items:
        relationships = (
            ("parent", record.parent_record_id, "MISSING_PARENT"),
            ("source_anchor", record.source_anchor_id, "MISSING_SOURCE_ANCHOR"),
            ("revision", record.revision_of_id, "MISSING_REVISION_TARGET"),
        )
        for relationship, reference, missing_code in relationships:
            if reference is None or reference == "":
                findings.append(SnapshotFinding(
                    record.record_id, relationship, "BLANK_UNCLASSIFIED",
                    "Reference is blank; intentional root/N/A cannot be distinguished from unknown.",
                    "info",
                ))
                continue
            referenced = index.get(reference)
            if referenced is None:
                findings.append(SnapshotFinding(
                    record.record_id, relationship, missing_code,
                    f"References missing record {reference}.", "error",
                ))
            elif relationship == "source_anchor" and referenced.branch_id != record.source_branch_id:
                findings.append(SnapshotFinding(
                    record.record_id, relationship, "SOURCE_BRANCH_MISMATCH",
                    f"Declared source branch {record.source_branch_id}; anchor belongs to {referenced.branch_id}.",
                    "error",
                ))

    # Report each distinct parent cycle once, while retaining its member IDs.
    reported_cycles: set[frozenset[str]] = set()
    for start in index:
        path: list[str] = []
        positions: dict[str, int] = {}
        current: str | None = start
        while current is not None and current in index:
            if current in positions:
                cycle_members = path[positions[current]:]
                cycle_key = frozenset(cycle_members)
                if cycle_key not in reported_cycles:
                    reported_cycles.add(cycle_key)
                    chain = cycle_members + [current]
                    findings.append(SnapshotFinding(
                        current, "parent", "PARENT_CYCLE",
                        " -> ".join(chain), "error",
                    ))
                break
            positions[current] = len(path)
            path.append(current)
            parent_id = index[current].parent_record_id
            current = parent_id if parent_id else None

    findings.sort(key=lambda f: (
        f.record_id or "", f.relationship, f.code, f.detail
    ))
    return SnapshotAudit(len(items), tuple(findings))
