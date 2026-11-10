"""Read-only reconciliation of externally supplied creation-event evidence.

This module does not mutate MemoryStore or promote any candidate version.
The evidence envelope is deliberately separate from the MRP-001 record model.
"""
from __future__ import annotations

from dataclasses import dataclass
from enum import Enum
from typing import Iterable


class Outcome(str, Enum):
    CONSISTENT = "CONSISTENT"
    INCOMPLETE = "INCOMPLETE"
    CONFLICT = "CONFLICT"
    AMBIGUOUS = "AMBIGUOUS"
    UNCERTAIN = "UNCERTAIN"
    NOT_FOUND = "NOT_FOUND"


@dataclass(frozen=True)
class CreationEvidence:
    event_id: str | None
    record_id: str | None
    creation_type: str | None
    record_digest: str | None
    sequence: int | None


@dataclass(frozen=True)
class ReconciliationResult:
    outcome: Outcome
    findings: tuple[str, ...]
    matching_event_ids: tuple[str, ...]


def reconcile_creation_event(
    *,
    expected_event_id: str,
    expected_record_id: str,
    expected_creation_type: str,
    expected_record_digest: str,
    evidence: Iterable[CreationEvidence],
    history_state: str = "valid",
) -> ReconciliationResult:
    """Compare evidence without changing it.

    history_state is one of: valid, missing, malformed, unreadable.
    A missing history source yields INCOMPLETE; malformed/unreadable history
    yields UNCERTAIN unless a higher-priority direct conflict is found.
    """
    rows = tuple(evidence)
    findings: list[str] = []
    matches: list[CreationEvidence] = []
    direct_conflicts: list[str] = []
    incomplete: list[str] = []

    if history_state not in {"valid", "missing", "malformed", "unreadable"}:
        raise ValueError("history_state must be valid, missing, malformed, or unreadable")

    if history_state == "missing":
        incomplete.append("history source is missing")
    elif history_state in {"malformed", "unreadable"}:
        findings.append(f"history source is {history_state}")

    if not rows:
        if direct_conflicts:
            outcome = Outcome.CONFLICT
        elif history_state in {"malformed", "unreadable"}:
            outcome = Outcome.UNCERTAIN
        elif history_state == "missing":
            outcome = Outcome.INCOMPLETE
        else:
            outcome = Outcome.NOT_FOUND
        return ReconciliationResult(outcome, tuple(findings + incomplete + ["no evidence entries supplied"]), ())

    event_rows = [row for row in rows if row.event_id == expected_event_id]
    if not event_rows:
        outcome = Outcome.UNCERTAIN if history_state in {"malformed", "unreadable"} else (
            Outcome.INCOMPLETE if history_state == "missing" else Outcome.NOT_FOUND
        )
        return ReconciliationResult(outcome, tuple(findings + incomplete + ["requested event was not located"]), ())

    for index, row in enumerate(event_rows):
        missing = []
        for field_name in ("event_id", "record_id", "creation_type", "record_digest", "sequence"):
            if getattr(row, field_name) is None:
                missing.append(field_name)
        if missing:
            incomplete.append(f"evidence entry {index} lacks {', '.join(missing)}")
            continue
        if row.sequence < 0:
            direct_conflicts.append(f"evidence entry {index} has an invalid negative sequence")
            continue
        if row.record_id != expected_record_id:
            direct_conflicts.append(f"evidence entry {index} has a different record identity")
        if row.creation_type != expected_creation_type:
            direct_conflicts.append(f"evidence entry {index} has a different creation type")
        if row.record_digest != expected_record_digest:
            direct_conflicts.append(f"evidence entry {index} has a different record digest")
        if (
            row.record_id == expected_record_id
            and row.creation_type == expected_creation_type
            and row.record_digest == expected_record_digest
            and row.sequence >= 0
        ):
            matches.append(row)

    findings.extend(direct_conflicts)
    findings.extend(incomplete)
    matching_ids = tuple(row.event_id for row in matches if row.event_id is not None)

    # Frozen precedence: CONFLICT > AMBIGUOUS > UNCERTAIN > INCOMPLETE
    # > CONSISTENT > NOT_FOUND.
    if direct_conflicts:
        outcome = Outcome.CONFLICT
    elif len(event_rows) > 1:
        outcome = Outcome.AMBIGUOUS
        findings.append("multiple entries share the requested event identity")
    elif history_state in {"malformed", "unreadable"}:
        outcome = Outcome.UNCERTAIN
    elif incomplete or history_state == "missing":
        outcome = Outcome.INCOMPLETE
    elif len(matches) == 1:
        outcome = Outcome.CONSISTENT
    else:
        outcome = Outcome.UNCERTAIN
        findings.append("available evidence does not establish a unique valid match")

    return ReconciliationResult(outcome, tuple(findings), matching_ids)
