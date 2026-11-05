"""Shared Nine Builders orchestration adapter.

This is a reference orchestration boundary, not an autonomous multi-agent runtime.
All Builder roles receive the same immutable pre-action decision. Capability-dependent
work is dispatched only when the shared gate permits it.
"""
from copy import deepcopy
from datetime import date
from typing import Callable, Any

from check_operational_limits import evaluate

BUILDER_ROLES = {
    "Huginn": "Hunt for current limit evidence and source references.",
    "Muninn": "Maintain continuity and compare current state with prior observations.",
    "Kvasir": "Interpret limits and distinguish similar constraint types.",
    "Mari": "Translate constraints into clear user-facing language and alternatives.",
    "Höðr": "Challenge unsupported assumptions and identify blind spots.",
    "Týr": "Enforce safety, authorization, and no-destructive-workaround gates.",
    "Loki": "Find legitimate alternatives without disguising or bypassing limits.",
    "Odin": "Coordinate the overall operating picture and priority decisions.",
    "Baldr": "Verify the record, timestamp, and claims before reporting.",
}

PERMITTED_DECISIONS = {"proceed", "proceed_with_constraints"}


def prepare_builder_cycle(register: dict, capability: str, action: str | None = None,
                          as_of: date | None = None, max_age_days: int = 7) -> dict:
    """Create one shared gate decision for all nine Builder roles."""
    decision, exit_code = evaluate(
        register, capability, action=action, as_of=as_of, max_age_days=max_age_days
    )
    shared = {
        "capability": capability,
        "action": action,
        "gate_decision": decision,
        "gate_exit_code": exit_code,
        "dispatch_permitted": decision.get("decision") in PERMITTED_DECISIONS,
        "roles": deepcopy(BUILDER_ROLES),
        "role_contexts": {},
    }
    # Each role receives an identical copy of the same gate result, not an independent
    # interpretation that could accidentally weaken the shared stop condition.
    for role, responsibility in BUILDER_ROLES.items():
        shared["role_contexts"][role] = {
            "responsibility": responsibility,
            "shared_gate_decision": deepcopy(decision),
            "must_honor_shared_gate": True,
        }
    return shared


def dispatch_builder_action(cycle: dict, action_callback: Callable[[], Any],
                            requires_explicit_authorization: bool = False,
                            authorization_granted: bool = False) -> dict:
    """Run the callback only after gate approval and any separately required authorization."""
    if requires_explicit_authorization and not authorization_granted:
        return {
            "dispatched": False,
            "decision": "authorization_required",
            "reason": "This action requires explicit authorization not supplied to the orchestrator.",
            "result": None,
        }
    if not cycle.get("dispatch_permitted", False):
        return {
            "dispatched": False,
            "decision": cycle.get("gate_decision", {}).get("decision", "recheck_required"),
            "reason": cycle.get("gate_decision", {}).get("reason", "No valid shared gate approval."),
            "result": None,
        }
    return {"dispatched": True, "decision": "dispatched",
            "reason": "Shared gate and authorization checks passed.", "result": action_callback()}
