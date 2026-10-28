"""Ledger-anchored recovery decisions and freshness checks."""
from __future__ import annotations

from dataclasses import dataclass
from typing import Literal

from .recovery import RecoveryDecision, RecoveryFacts, decide_recovery
from .sqlite_ledger import SQLiteAttemptLedger

LedgerRecoveryStatus = Literal["MATCHED_ATTEMPT", "LEDGER_CONFLICT", "ATTEMPT_NOT_FOUND"]
FreshnessStatus = Literal["CURRENT_AT_CHECK", "STALE_DECISION", "ATTEMPT_NOT_FOUND", "LEDGER_CONFLICT"]


@dataclass(frozen=True)
class LedgerRecoveryDecision:
    ledger_status: LedgerRecoveryStatus
    project_id: str
    operation_id: str
    attempt_id: str
    attempt_number: int | None
    recovery: RecoveryDecision


@dataclass(frozen=True)
class RecoveryFreshnessCheck:
    """A point-in-time freshness result, not a durable authorization token."""
    status: FreshnessStatus
    project_id: str
    operation_id: str
    attempt_id: str
    expected_attempt_number: int | None
    current_attempt_id: str | None
    current_attempt_number: int | None


def decide_recovery_with_ledger(ledger: SQLiteAttemptLedger, project_id: str,
                                facts: RecoveryFacts) -> LedgerRecoveryDecision:
    """Require the referenced attempt to be the latest stored attempt for its operation."""
    if not isinstance(project_id, str) or not project_id.strip():
        return LedgerRecoveryDecision("LEDGER_CONFLICT", str(project_id),
            getattr(facts, "operation_id", ""), getattr(facts, "attempt_id", ""), None,
            RecoveryDecision("STOP_CONFLICT", "project_id must be non-empty text"))
    if not isinstance(facts, RecoveryFacts):
        return LedgerRecoveryDecision("LEDGER_CONFLICT", project_id, "", "", None,
            RecoveryDecision("STOP_CONFLICT", "facts must be RecoveryFacts"))
    attempts = ledger.list_attempts(project_id, facts.operation_id)
    matching = [item for item in attempts if item.attempt_id == facts.attempt_id]
    if not matching:
        return LedgerRecoveryDecision("ATTEMPT_NOT_FOUND", project_id, facts.operation_id,
            facts.attempt_id, None,
            RecoveryDecision("STOP_CONFLICT", "recovery attempt is absent from the durable ledger"))
    matched = matching[0]
    if matched != attempts[-1]:
        return LedgerRecoveryDecision("LEDGER_CONFLICT", project_id, facts.operation_id,
            facts.attempt_id, matched.attempt_number,
            RecoveryDecision("STOP_CONFLICT", "recovery facts refer to a stale attempt; newer ledger attempts exist"))
    return LedgerRecoveryDecision("MATCHED_ATTEMPT", project_id, facts.operation_id,
        facts.attempt_id, matched.attempt_number, decide_recovery(facts))


def check_recovery_decision_freshness(
    ledger: SQLiteAttemptLedger, decision: LedgerRecoveryDecision
) -> RecoveryFreshnessCheck:
    """Recheck that the decision still names the latest attempt.

    This is only a point-in-time check. A competing append can occur immediately
    after it returns, so it must not be treated as atomic authorization to perform
    an external side effect.
    """
    if not isinstance(decision, LedgerRecoveryDecision):
        return RecoveryFreshnessCheck("LEDGER_CONFLICT", "", "", "", None, None, None)
    if decision.ledger_status != "MATCHED_ATTEMPT" or decision.attempt_number is None:
        return RecoveryFreshnessCheck(
            "LEDGER_CONFLICT", decision.project_id, decision.operation_id,
            decision.attempt_id, decision.attempt_number, None, None
        )
    attempts = ledger.list_attempts(decision.project_id, decision.operation_id)
    if not attempts or not any(a.attempt_id == decision.attempt_id for a in attempts):
        return RecoveryFreshnessCheck(
            "ATTEMPT_NOT_FOUND", decision.project_id, decision.operation_id,
            decision.attempt_id, decision.attempt_number,
            attempts[-1].attempt_id if attempts else None,
            attempts[-1].attempt_number if attempts else None
        )
    current = attempts[-1]
    status: FreshnessStatus = (
        "CURRENT_AT_CHECK"
        if current.attempt_id == decision.attempt_id
        and current.attempt_number == decision.attempt_number
        else "STALE_DECISION"
    )
    return RecoveryFreshnessCheck(
        status, decision.project_id, decision.operation_id, decision.attempt_id,
        decision.attempt_number, current.attempt_id, current.attempt_number
    )
