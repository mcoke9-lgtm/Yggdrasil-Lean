"""Integration gate between alternative-work recommendations and recovery facts.

This module does not execute work. It prevents a retry recommendation from being
treated as authorization when recovery says the previous side effect is uncertain.
"""
from __future__ import annotations

from dataclasses import dataclass
from typing import Literal

from .alternative_work import WorkDecision
from .recovery import RecoveryDecision


GateOutcome = Literal["ALLOW_RETRY", "BLOCK_RETRY", "NO_RETRY_RECOMMENDED"]


@dataclass(frozen=True)
class RetryGateDecision:
    outcome: GateOutcome
    retry_allowed: bool
    work_outcome: str
    recovery_outcome: str
    reason: str


def gate_retry(work: WorkDecision, recovery: RecoveryDecision) -> RetryGateDecision:
    """Gate RETRY_JUSTIFIED through the side-effect recovery decision."""
    if work.outcome != "RETRY_JUSTIFIED":
        return RetryGateDecision(
            "NO_RETRY_RECOMMENDED", False, work.outcome, recovery.outcome,
            "work evaluator did not recommend a retry",
        )
    if recovery.outcome in ("SAFE_TO_START", "SAFE_TO_REPLAY"):
        return RetryGateDecision(
            "ALLOW_RETRY", True, work.outcome, recovery.outcome,
            "recovery decision permits starting or replaying this action",
        )
    return RetryGateDecision(
        "BLOCK_RETRY", False, work.outcome, recovery.outcome,
        "recovery decision does not establish that retry is safe; reconcile or resolve the conflict first",
    )
