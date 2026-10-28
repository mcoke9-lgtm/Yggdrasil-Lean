"""Pure contract for an append-only retry-attempt ledger (AF-LEDGER-001 draft).

This module does not persist records or claim cross-process durability. It defines
validation/idempotency behavior a future storage adapter must enforce atomically.
"""
from __future__ import annotations

from dataclasses import dataclass
from typing import Literal


AppendOutcome = Literal["APPEND", "IDEMPOTENT_REPLAY", "REJECT_CONFLICT"]


@dataclass(frozen=True)
class AttemptRecord:
    project_id: str
    operation_id: str
    attempt_id: str
    idempotency_key: str
    attempt_number: int
    operation_fingerprint: str
    branch_id: str
    checkpoint_id: str
    result_status: str
    evidence_reference: str | None = None


@dataclass(frozen=True)
class AppendDecision:
    outcome: AppendOutcome
    reason: str


def _valid(record: AttemptRecord) -> str | None:
    for name in (
        "project_id", "operation_id", "attempt_id", "idempotency_key",
        "operation_fingerprint", "branch_id", "checkpoint_id", "result_status",
    ):
        value = getattr(record, name)
        if not isinstance(value, str) or not value.strip():
            return f"{name} must be non-empty text"
    if (not isinstance(record.attempt_number, int)
            or isinstance(record.attempt_number, bool) or record.attempt_number < 1):
        return "attempt_number must be a positive integer"
    if record.evidence_reference is not None and (
            not isinstance(record.evidence_reference, str)
            or not record.evidence_reference.strip()):
        return "evidence_reference must be non-empty text or None"
    return None


def decide_append(
    existing: tuple[AttemptRecord, ...] | list[AttemptRecord],
    proposed: AttemptRecord,
) -> AppendDecision:
    """Classify an append without side effects; storage must enforce this atomically.

    operation_id is stable across retries and chat transitions. attempt_id and
    idempotency_key identify one append. branch/checkpoint are provenance, not
    operation identity. A retry is a new attempt number and may have a changed
    operation_fingerprint; it is not a new operation merely because a chat changed.
    """
    if not isinstance(existing, (tuple, list)):
        return AppendDecision("REJECT_CONFLICT", "existing attempts must be a tuple or list")
    if any(not isinstance(item, AttemptRecord) for item in existing):
        return AppendDecision("REJECT_CONFLICT", "existing ledger contains an invalid record")
    if not isinstance(proposed, AttemptRecord):
        return AppendDecision("REJECT_CONFLICT", "proposed attempt has invalid type")
    invalid = _valid(proposed)
    if invalid:
        return AppendDecision("REJECT_CONFLICT", invalid)
    for item in existing:
        invalid_existing = _valid(item)
        if invalid_existing:
            return AppendDecision("REJECT_CONFLICT", f"existing ledger invalid: {invalid_existing}")

    # Same identifiers with different payloads are conflicts; exact repeats are safe replays.
    for item in existing:
        if item.attempt_id == proposed.attempt_id or item.idempotency_key == proposed.idempotency_key:
            if item == proposed:
                return AppendDecision("IDEMPOTENT_REPLAY", "exact attempt already exists")
            return AppendDecision("REJECT_CONFLICT", "attempt_id or idempotency_key reused with different content")

    operation_attempts = [
        item for item in existing
        if item.project_id == proposed.project_id and item.operation_id == proposed.operation_id
    ]
    if any(item.attempt_number == proposed.attempt_number for item in operation_attempts):
        return AppendDecision("REJECT_CONFLICT", "attempt number already exists for this operation")
    expected = max((item.attempt_number for item in operation_attempts), default=0) + 1
    if proposed.attempt_number != expected:
        return AppendDecision("REJECT_CONFLICT", f"attempt_number must be next in sequence: {expected}")

    return AppendDecision("APPEND", "new attempt is well-formed and sequential")
