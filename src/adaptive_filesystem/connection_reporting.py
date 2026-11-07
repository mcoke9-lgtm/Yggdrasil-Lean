"""Minimal, provider-neutral connection outcome reporting contract.

This is a synthetic contract helper, not a production connector or runtime.
It records attempted, intended, applied, and evidenced work separately.
"""
from copy import deepcopy

OUTCOMES = {"SUCCESS", "PARTIAL", "BLOCKED", "FAILED"}


def build_connection_report(
    *,
    attempt_id,
    target,
    outcome,
    started_at,
    completed_at,
    intended_changes=None,
    applied_changes=None,
    evidence=None,
    error=None,
):
    """Return a report for every completed connection attempt.

    Callers must invoke this for every outcome, including blocked/failed.
    The helper does not claim that applied changes are independently verified.
    """
    if not attempt_id or not target or not started_at or not completed_at:
        raise ValueError("attempt identity, target, and timestamps are required")
    if outcome not in OUTCOMES:
        raise ValueError("outcome must be SUCCESS, PARTIAL, BLOCKED, or FAILED")
    intended = deepcopy(intended_changes or [])
    applied = deepcopy(applied_changes or [])
    evidence_items = deepcopy(evidence or [])
    return {
        "report_type": "post_connection",
        "attempt_id": attempt_id,
        "target": target,
        "outcome": outcome,
        "started_at": started_at,
        "completed_at": completed_at,
        "intended_changes": intended,
        "applied_changes": applied,
        "evidence": evidence_items,
        "error": error,
        "verification_status": "UNVERIFIED",
    }
