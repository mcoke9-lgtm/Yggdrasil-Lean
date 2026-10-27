"""Recovery decisions anchored to the persisted SQLite attempt ledger."""
from __future__ import annotations
from dataclasses import dataclass
from typing import Literal
from .recovery import RecoveryDecision, RecoveryFacts, decide_recovery
from .sqlite_ledger import SQLiteAttemptLedger

LedgerRecoveryStatus = Literal["MATCHED_ATTEMPT", "LEDGER_CONFLICT", "ATTEMPT_NOT_FOUND"]

@dataclass(frozen=True)
class LedgerRecoveryDecision:
    ledger_status: LedgerRecoveryStatus
    project_id: str
    operation_id: str
    attempt_id: str
    attempt_number: int | None
    recovery: RecoveryDecision

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
