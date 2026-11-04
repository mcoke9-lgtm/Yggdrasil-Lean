"""Crash-aware orchestration for the external-effect prototype.

The orchestrator records intent before a remote call, never blindly repeats an
uncertain call, records a matching receipt, and only then commits the local
execution ledger. Recovery is lookup-only: it never reissues the remote action.
"""
import json
from collections.abc import Mapping


class ExternalEffectOrchestrator:
    """Coordinate readiness, intent, remote execution, receipt, and ledger commit."""

    def __init__(self, readiness_gate, receipt_store, executor, execution_ledger):
        if not callable(getattr(readiness_gate, "check", None)):
            raise TypeError("readiness_gate must support check")
        if not callable(getattr(receipt_store, "register_intent", None)):
            raise TypeError("receipt_store must support register_intent")
        if not callable(getattr(receipt_store, "record_receipt", None)):
            raise TypeError("receipt_store must support record_receipt")
        if not callable(getattr(receipt_store, "inspect", None)):
            raise TypeError("receipt_store must support inspect")
        if not callable(getattr(executor, "execute", None)):
            raise TypeError("executor must support execute")
        if not callable(getattr(executor, "lookup", None)):
            raise TypeError("executor must support lookup")
        if not callable(getattr(execution_ledger, "commit_once", None)):
            raise TypeError("execution_ledger must support commit_once")
        self._readiness_gate = readiness_gate
        self._receipt_store = receipt_store
        self._executor = executor
        self._execution_ledger = execution_ledger

    @staticmethod
    def _bindings(ready, environment_request):
        return {
            "authorization_id": ready["authorization_id"],
            "action_fingerprint": ready["action_fingerprint"],
            "environment_id": environment_request["environment_id"],
            "environment_action": environment_request["environment_action"],
        }

    def _record_and_commit(self, idempotency_key, bindings, response, effect_executed):
        if not isinstance(response, Mapping) or response.get("status") != "APPLIED":
            return {"status": "RECONCILIATION_REQUIRED", "reason": "remote_outcome_not_confirmed"}
        if any(response.get(field) != value for field, value in bindings.items()):
            return {"status": "RECONCILIATION_REQUIRED", "reason": "remote_receipt_binding_mismatch"}
        receipt_id = response.get("receipt_id")
        if not isinstance(receipt_id, str) or not receipt_id.strip() or "outcome" not in response:
            return {"status": "RECONCILIATION_REQUIRED", "reason": "remote_receipt_malformed"}

        try:
            receipt = self._receipt_store.record_receipt(
                idempotency_key=idempotency_key,
                external_receipt_id=receipt_id,
                outcome=response["outcome"],
                **bindings,
            )
        except Exception:
            return {"status": "RECONCILIATION_REQUIRED", "reason": "receipt_persistence_unconfirmed"}
        if receipt.get("status") not in ("RECEIVED", "REPLAY"):
            return {"status": "RECONCILIATION_REQUIRED", "reason": "receipt_rejected"}

        ledger_outcome = json.dumps(
            {
                "idempotency_key": idempotency_key,
                "external_receipt_id": receipt_id,
                "outcome_digest": self._receipt_store.inspect(idempotency_key)["outcome_digest"],
            },
            sort_keys=True,
            separators=(",", ":"),
        )
        try:
            committed = self._execution_ledger.commit_once(
                bindings["authorization_id"],
                bindings["action_fingerprint"],
                ledger_outcome,
            )
        except Exception:
            return {"status": "RECONCILIATION_REQUIRED", "reason": "ledger_commit_unconfirmed"}
        return {
            "status": committed["status"],
            "ledger": committed,
            "receipt": receipt,
            "effect_executed": effect_executed,
        }

    def execute(self, idempotency_key, transition_request, environment_request, payload):
        """Start one effect; any ambiguous remote result becomes reconciliation."""
        existing = self._receipt_store.inspect(idempotency_key)
        if existing is not None:
            existing_bindings = {
                "authorization_id": existing["authorization_id"],
                "action_fingerprint": existing["action_fingerprint"],
                "environment_id": existing["environment_id"],
                "environment_action": existing["environment_action"],
            }
            if (
                transition_request.get("authorization_id") != existing_bindings["authorization_id"]
                or transition_request.get("action_fingerprint") != existing_bindings["action_fingerprint"]
                or environment_request.get("environment_id") != existing_bindings["environment_id"]
                or environment_request.get("environment_action") != existing_bindings["environment_action"]
            ):
                return {"status": "REJECT", "reason": "idempotency_key_conflict"}
            if existing["status"] == "RECEIVED":
                return self._commit_existing(idempotency_key, existing_bindings, existing)
            return {"status": "RECONCILIATION_REQUIRED", "reason": "existing_intent_requires_lookup"}

        if not isinstance(transition_request, Mapping) or not isinstance(environment_request, Mapping):
            return {"status": "REJECT", "reason": "request_invalid"}

        ready = self._readiness_gate.check(transition_request, environment_request)
        if not isinstance(ready, Mapping) or ready.get("status") != "READY":
            return {"status": "REJECT", "reason": "execution_not_ready"}

        bindings = self._bindings(ready, environment_request)
        if (
            transition_request.get("authorization_id") != bindings["authorization_id"]
            or transition_request.get("action_fingerprint") != bindings["action_fingerprint"]
        ):
            return {"status": "REJECT", "reason": "transition_binding_mismatch"}

        intent = self._receipt_store.register_intent(
            idempotency_key=idempotency_key, **bindings
        )
        if intent.get("status") != "INTENDED":
            return {"status": "RECONCILIATION_REQUIRED", "reason": "intent_not_new"}

        try:
            response = self._executor.execute(
                idempotency_key=idempotency_key,
                environment_id=bindings["environment_id"],
                environment_action=bindings["environment_action"],
                payload=payload,
            )
        except Exception:
            self._receipt_store.mark_reconciliation_required(idempotency_key)
            return {"status": "RECONCILIATION_REQUIRED", "reason": "remote_call_outcome_unknown"}

        return self._record_and_commit(idempotency_key, bindings, response, effect_executed=True)

    def _commit_existing(self, idempotency_key, bindings, existing):
        ledger_outcome = json.dumps(
            {
                "idempotency_key": idempotency_key,
                "external_receipt_id": existing["external_receipt_id"],
                "outcome_digest": existing["outcome_digest"],
            },
            sort_keys=True,
            separators=(",", ":"),
        )
        try:
            committed = self._execution_ledger.commit_once(
                bindings["authorization_id"],
                bindings["action_fingerprint"],
                ledger_outcome,
            )
        except Exception:
            return {"status": "RECONCILIATION_REQUIRED", "reason": "ledger_commit_unconfirmed"}
        return {
            "status": committed["status"],
            "ledger": committed,
            "receipt": {"status": "REPLAY", "external_receipt_id": existing["external_receipt_id"]},
            "effect_executed": False,
        }

    def reconcile(self, idempotency_key):
        """Lookup remote state only; never execute/retry the effect during recovery."""
        existing = self._receipt_store.inspect(idempotency_key)
        if existing is None:
            return {"status": "REJECT", "reason": "intent_unknown"}
        bindings = {
            "authorization_id": existing["authorization_id"],
            "action_fingerprint": existing["action_fingerprint"],
            "environment_id": existing["environment_id"],
            "environment_action": existing["environment_action"],
        }
        if existing["status"] == "RECEIVED":
            return self._commit_existing(idempotency_key, bindings, existing)

        try:
            response = self._executor.lookup(
                idempotency_key=idempotency_key,
                environment_id=existing["environment_id"],
                environment_action=existing["environment_action"],
            )
        except Exception:
            self._receipt_store.mark_reconciliation_required(idempotency_key)
            return {"status": "RECONCILIATION_REQUIRED", "reason": "remote_lookup_failed"}

        if not isinstance(response, Mapping):
            return {"status": "RECONCILIATION_REQUIRED", "reason": "remote_lookup_malformed"}
        if response.get("status") == "NOT_FOUND":
            self._receipt_store.mark_reconciliation_required(idempotency_key)
            return {"status": "RECONCILIATION_REQUIRED", "reason": "remote_effect_not_located"}
        if response.get("status") != "FOUND":
            self._receipt_store.mark_reconciliation_required(idempotency_key)
            return {"status": "RECONCILIATION_REQUIRED", "reason": "remote_state_unresolved"}

        return self._record_and_commit(idempotency_key, bindings, {
            "status": "APPLIED",
            "receipt_id": response.get("receipt_id"),
            "outcome": response.get("outcome"),
            **{field: response.get(field) for field in bindings},
        }, effect_executed=False)
