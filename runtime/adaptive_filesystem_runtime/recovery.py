""""Pure decision rules for recovering uncertain external side effects.

A ledger records what the caller knows about an attempt. It cannot infer whether
an external action happened when acknowledgement/evidence is missing.
"""
from __future__ import annotations

from dataclasses import dataclass
from typing import Literal


RecoveryOutcome = Literal[
    "SAFE_TO_START", "SAFE_TO_REPLAY", "RECONCILE_REQUIRED",
    "CONFIRMED_COMPLETE", "STOP_CONFLICT",
]


@dataclass(frozen=True)
class RecoveryFacts:
    operation_id: str
    attempt_id: str
    action_class: str
    durable_intent_recorded: bool
    external_action_started: bool
    outcome_evidence: str | None
    idempotent_external_action: bool
    idempotency_key_supported_by_target: bool
    explicit_reconciliation_result: str | None = None


@dataclass(frozen=True)
class RecoveryDecision:
    outcome: RecoveryOutcome
    reason: str


def decide_recovery(facts: RecoveryFacts) -> RecoveryDecision:
    """Fail closed when an external side effect may have happened.

    Replaying is considered safe only when the target itself supports the same
    stable idempotency key and the action is declared idempotent. Missing outcome
    evidence after a started non-idempotent action requires reconciliation.
    """
    for name in ("operation_id", "attempt_id", "action_class"):
        value = getattr(facts, name)
        if not isinstance(value, str) or not value.strip():
            return RecoveryDecision("STOP_CONFLICT", f"{name} must be non-empty text")
    for name in (
        "durable_intent_recorded", "external_action_started",
        "idempotent_external_action", "idempotency_key_supported_by_target",
    ):
        if not isinstance(getattr(facts, name), bool):
            return RecoveryDecision("STOP_CONFLICT", f"{name} must be boolean")
    for name in ("outcome_evidence", "explicit_reconciliation_result"):
        value = getattr(facts, name)
        if value is not None and (not isinstance(value, str) or not value.strip()):
            return RecoveryDecision("STOP_CONFLICT", f"{name} must be non-empty text or None")

    # Do not let a missing intent record bypass contradictory outcome claims.
    if not facts.durable_intent_recorded:
        if facts.outcome_evidence or facts.explicit_reconciliation_result is not None:
            return RecoveryDecision(
                "STOP_CONFLICT",
                "outcome evidence or reconciliation conflicts with missing durable intent",
            )
        if facts.external_action_started:
            return RecoveryDecision(
                "RECONCILE_REQUIRED",
                "external action may have started without durable intent; do not repeat blindly",
            )
        return RecoveryDecision("SAFE_TO_START", "no durable intent or external start is recorded")

    if facts.explicit_reconciliation_result == "completed":
        if facts.outcome_evidence:
            return RecoveryDecision("CONFIRMED_COMPLETE", "reconciliation confirms completion with evidence")
        return RecoveryDecision("STOP_CONFLICT", "completion claim requires outcome evidence")
    if facts.explicit_reconciliation_result == "not_started":
        if facts.external_action_started or facts.outcome_evidence:
            return RecoveryDecision(
                "STOP_CONFLICT",
                "not-started reconciliation conflicts with recorded start or outcome evidence",
            )
        return RecoveryDecision("SAFE_TO_START", "reconciliation confirms the action did not start")
    if facts.explicit_reconciliation_result is not None:
        return RecoveryDecision("STOP_CONFLICT", "unrecognized reconciliation result")

    if facts.outcome_evidence:
        return RecoveryDecision("CONFIRMED_COMPLETE", "outcome evidence is present")
    if not facts.external_action_started:
        return RecoveryDecision("SAFE_TO_START", "durable intent exists and external action has not started")
    if facts.idempotent_external_action and facts.idempotency_key_supported_by_target:
        return RecoveryDecision("SAFE_TO_REPLAY", "target-enforced idempotency makes replay safe")
    return RecoveryDecision(
        "RECONCILE_REQUIRED",
        "action may have occurred; outcome is unknown and safe replay is not established",
    )
"