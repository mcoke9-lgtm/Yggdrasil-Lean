"""Fail-closed boundary between an authority verifier and the execution ledger."""
from collections.abc import Mapping

from adaptive_filesystem.action_fingerprint import action_fingerprint


class VerifiedAuthorizationGate:
    """Register only verifier-approved records bound to a canonical action."""

    def __init__(self, verifier, ledger):
        if not callable(verifier):
            raise TypeError("verifier must be callable")
        if not callable(getattr(ledger, "register_verified_authorization", None)):
            raise TypeError("ledger must support verified-authorization registration")
        self._verifier = verifier
        self._ledger = ledger

    def authorize(self, request):
        """Verify and register an authorization for one exact action request.

        The caller's supplied fingerprint is checked against a locally derived
        fingerprint. The verifier must return status='VERIFIED' and the exact
        authorization ID and derived fingerprint. The verifier remains the
        trust root; this gate does not authenticate it or execute effects.
        """
        if not isinstance(request, Mapping):
            return {"status": "REJECT", "reason": "request_invalid"}
        authorization_id = request.get("authorization_id")
        if not isinstance(authorization_id, str) or not authorization_id.strip():
            return {"status": "REJECT", "reason": "request_field_missing"}

        try:
            derived_fingerprint = action_fingerprint(dict(request))
        except (TypeError, ValueError):
            return {"status": "REJECT", "reason": "action_description_invalid"}

        if request.get("action_fingerprint") != derived_fingerprint:
            return {"status": "REJECT", "reason": "caller_fingerprint_mismatch"}

        verified_request = dict(request)
        verified_request["action_fingerprint"] = derived_fingerprint
        try:
            decision = self._verifier(verified_request)
        except Exception:
            return {"status": "REJECT", "reason": "verifier_error"}

        if not isinstance(decision, Mapping):
            return {"status": "REJECT", "reason": "verifier_response_invalid"}
        if decision.get("status") != "VERIFIED":
            return {"status": "REJECT", "reason": "authority_not_verified"}
        if decision.get("authorization_id") != authorization_id:
            return {"status": "REJECT", "reason": "authorization_id_mismatch"}
        if decision.get("action_fingerprint") != derived_fingerprint:
            return {"status": "REJECT", "reason": "action_fingerprint_mismatch"}

        try:
            self._ledger.register_verified_authorization(
                authorization_id, derived_fingerprint
            )
        except Exception:
            return {"status": "REJECT", "reason": "ledger_registration_failed"}

        return {
            "status": "REGISTERED",
            "authorization_id": authorization_id,
            "action_fingerprint": derived_fingerprint,
        }
