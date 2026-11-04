"""Separate environment-action permission check for the AF prototype.

Transition authorization and permission to act in the current environment are
independent decisions. This module never treats one as evidence of the other.
"""
from collections.abc import Mapping


class EnvironmentPermissionGate:
    """Ask an independent policy provider whether an exact action may run here."""

    def __init__(self, permission_provider):
        if not callable(permission_provider):
            raise TypeError("permission_provider must be callable")
        self._permission_provider = permission_provider

    def check(self, request):
        """Return PERMITTED only for an exact, independently approved action.

        Required request fields: authorization_id, action_fingerprint, actor,
        environment_id, and environment_action. The provider must return a
        mapping with status='PERMITTED' and exact matches for all binding fields.
        """
        if not isinstance(request, Mapping):
            return {"status": "REJECT", "reason": "request_invalid"}

        fields = (
            "authorization_id",
            "action_fingerprint",
            "actor",
            "environment_id",
            "environment_action",
        )
        if any(
            not isinstance(request.get(field), str) or not request[field].strip()
            for field in fields
        ):
            return {"status": "REJECT", "reason": "request_field_missing"}

        try:
            decision = self._permission_provider(dict(request))
        except Exception:
            return {"status": "REJECT", "reason": "permission_provider_error"}

        if not isinstance(decision, Mapping):
            return {"status": "REJECT", "reason": "permission_response_invalid"}
        if decision.get("status") != "PERMITTED":
            return {"status": "REJECT", "reason": "environment_permission_denied"}
        for field in fields:
            if decision.get(field) != request[field]:
                return {"status": "REJECT", "reason": field + "_mismatch"}

        return {
            "status": "PERMITTED",
            "authorization_id": request["authorization_id"],
            "action_fingerprint": request["action_fingerprint"],
            "environment_id": request["environment_id"],
            "environment_action": request["environment_action"],
        }


class ExecutionReadinessGate:
    """Require both independent gates; never execute the requested effect."""

    def __init__(self, transition_gate, environment_gate):
        if not callable(getattr(transition_gate, "authorize", None)):
            raise TypeError("transition_gate must support authorize")
        if not callable(getattr(environment_gate, "check", None)):
            raise TypeError("environment_gate must support check")
        self._transition_gate = transition_gate
        self._environment_gate = environment_gate

    def check(self, transition_request, environment_request):
        try:
            transition = self._transition_gate.authorize(transition_request)
        except Exception:
            return {"status": "REJECT", "reason": "transition_gate_error"}
        if not isinstance(transition, Mapping) or transition.get("status") != "REGISTERED":
            return {"status": "REJECT", "reason": "transition_authorization_rejected"}

        try:
            environment = self._environment_gate.check(environment_request)
        except Exception:
            return {"status": "REJECT", "reason": "environment_gate_error"}
        if not isinstance(environment, Mapping) or environment.get("status") != "PERMITTED":
            return {"status": "REJECT", "reason": "environment_permission_rejected"}

        if (
            transition.get("authorization_id") != environment.get("authorization_id")
            or transition.get("action_fingerprint") != environment.get("action_fingerprint")
        ):
            return {"status": "REJECT", "reason": "authorization_binding_mismatch"}

        return {
            "status": "READY",
            "authorization_id": transition["authorization_id"],
            "action_fingerprint": transition["action_fingerprint"],
            "environment_id": environment["environment_id"],
            "environment_action": environment["environment_action"],
            "effect_executed": False,
        }
