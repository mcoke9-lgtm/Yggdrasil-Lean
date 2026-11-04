"""Fail-closed boundary between an authority verifier and the execution ledger.

The verifier is injected by the application and remains the trust root. This
adapter validates that the verifier's decision is bound to the exact request;
it does not authenticate the verifier, validate signatures, or execute effects.
"""
from collections.abc import Mapping


class VerifiedAuthorizationGate:
    """Register only verifier-approved records bound to the requested action."""

    def __init__(self, verifier, ledger):
        if not callable(verifier):
            raise TypeError("verifier must be callable")
        if not callable(getattr(ledger, "register_verified_authorization", None)):
            raise TypeError("ledger must support verified-authorization registration")
        self._verifier = verifier
        self._ledger = ledger

    def authorize(self, request):
        """Verify and register an authorization for one exact action request.

        Required request fields: authorization_id, action_fingerprint, actor,
        source_branch, source_checkpoint, target_branch, and scope.

        The verifier must return a mapping with status='VERIFIED' and matching
        authorization_id/action_fingerprint. All other outcomes fail closed.
        """
        if not isinstance(request, Mapping):
            return {"status": "REJECT", "reason": "request_invalid"}
        required = (
            "authorization_id",
            "action_fingerprint",
            "actor",
            "source_branch",
            "source_checkpoint",
            "target_branch",
            "scope",
        )
        if any(request.get(field) in (None, "", [], {}) for field in required):
            return {"status": "REJECT", "reason": "request_field_missing"}

        try:
            decision = self._verifier(dict(request))
        except Exception:
            return {"status": "REJECT", "reason": "verifier_error"}

        if not isinstance(decision, Mapping):
            return {"status": "REJECT", "reason": "verifier_response_invalid"}
        if decision.get("status") != "VERIFIED":
            return {"status": "REJECT", "reason": "authority_not_verified"}
        if decision.get("authorization_id") != request["authorization_id"]:
            return {"status": "REJECT", "reason": "authorization_id_mismatch"}
        if decision.get("action_fingerprint") != request["action_fingerprint"]:
            return {"status": "REJECT", "reason": "action_fingerprint_mismatch"}

        try:
            self._ledger.register_verified_authorization(
                request["authorization_id"], request["action_fingerprint"]
            )
        except Exception:
            # Duplicate IDs and persistence failures are not permission to proceed.
            return {"status": "REJECT", "reason": "ledger_registration_failed"}

        return {
            "status": "REGISTERED",
            "authorization_id": request["authorization_id"],
            "action_fingerprint": request["action_fingerprint"],
        }
