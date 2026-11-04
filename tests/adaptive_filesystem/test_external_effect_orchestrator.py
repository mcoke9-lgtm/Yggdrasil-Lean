"""Crash-recovery tests for ExternalEffectOrchestrator."""
import tempfile
import unittest
from pathlib import Path

from adaptive_filesystem.effect_receipts import SQLiteEffectReceiptStore
from adaptive_filesystem.execution_ledger import SQLiteExecutionLedger
from adaptive_filesystem.external_effect_orchestrator import ExternalEffectOrchestrator


AUTH = "auth-55"
FINGERPRINT = "sha256:55aa"
TRANSITION = {
    "authorization_id": AUTH,
    "action_fingerprint": FINGERPRINT,
}
ENVIRONMENT = {
    "authorization_id": AUTH,
    "action_fingerprint": FINGERPRINT,
    "actor": "executor",
    "environment_id": "test-repo",
    "environment_action": "write:branch-ref",
}


class ReadyGate:
    def __init__(self, ready=True):
        self.ready = ready
        self.calls = 0

    def check(self, transition_request, environment_request):
        self.calls += 1
        if not self.ready:
            return {"status": "REJECT"}
        return {
            "status": "READY",
            "authorization_id": AUTH,
            "action_fingerprint": FINGERPRINT,
            "environment_id": ENVIRONMENT["environment_id"],
            "environment_action": ENVIRONMENT["environment_action"],
            "effect_executed": False,
        }


class FakeExecutor:
    def __init__(self, lose_ack=False, lookup_status="NOT_FOUND"):
        self.remote = {}
        self.execute_calls = 0
        self.lookup_calls = 0
        self.lose_ack = lose_ack
        self.lookup_status = lookup_status

    def execute(self, idempotency_key, environment_id, environment_action, payload):
        self.execute_calls += 1
        receipt = {
            "status": "FOUND",
            "receipt_id": "remote-" + idempotency_key,
            "outcome": {"remote_result": payload},
            "authorization_id": AUTH,
            "action_fingerprint": FINGERPRINT,
            "environment_id": environment_id,
            "environment_action": environment_action,
        }
        self.remote[idempotency_key] = receipt
        if self.lose_ack:
            raise TimeoutError("remote succeeded but acknowledgement was lost")
        return {
            "status": "APPLIED",
            "receipt_id": receipt["receipt_id"],
            "outcome": receipt["outcome"],
            "authorization_id": AUTH,
            "action_fingerprint": FINGERPRINT,
            "environment_id": environment_id,
            "environment_action": environment_action,
        }

    def lookup(self, idempotency_key, environment_id, environment_action):
        self.lookup_calls += 1
        return self.remote.get(
            idempotency_key,
            {"status": self.lookup_status},
        )


class FailingOnceLedger:
    def __init__(self, ledger):
        self.ledger = ledger
        self.fail = True

    def commit_once(self, *args):
        if self.fail:
            self.fail = False
            raise RuntimeError("simulated crash before local ledger commit")
        return self.ledger.commit_once(*args)


class OrchestratorTests(unittest.TestCase):
    def setUp(self):
        self.tempdir = tempfile.TemporaryDirectory()
        root = Path(self.tempdir.name)
        self.receipts = SQLiteEffectReceiptStore(root / "receipts.sqlite")
        self.ledger = SQLiteExecutionLedger(root / "ledger.sqlite")
        self.ledger.register_verified_authorization(AUTH, FINGERPRINT)
        self.ready = ReadyGate()
        self.executor = FakeExecutor()
        self.orchestrator = ExternalEffectOrchestrator(
            self.ready, self.receipts, self.executor, self.ledger
        )

    def tearDown(self):
        self.tempdir.cleanup()

    def execute(self, key="effect-55"):
        return self.orchestrator.execute(
            key, TRANSITION, ENVIRONMENT, {"operation": "update"}
        )

    def test_OE01_normal_execution_receipt_then_ledger_commit(self):
        result = self.execute()
        self.assertEqual(result["status"], "COMMITTED")
        self.assertEqual(self.executor.execute_calls, 1)
        self.assertEqual(self.receipts.inspect("effect-55")["status"], "RECEIVED")
        self.assertEqual(self.ledger.inspect(AUTH)["status"], "committed")

    def test_OE02_remote_success_lost_ack_reconciles_without_reexecution(self):
        self.executor.lose_ack = True
        first = self.execute()
        self.assertEqual(first["status"], "RECONCILIATION_REQUIRED")
        self.assertEqual(self.receipts.inspect("effect-55")["status"], "RECONCILIATION_REQUIRED")
        recovered = self.orchestrator.reconcile("effect-55")
        self.assertEqual(recovered["status"], "COMMITTED")
        self.assertFalse(recovered["effect_executed"])
        self.assertEqual(self.executor.execute_calls, 1)
        self.assertEqual(self.executor.lookup_calls, 1)

    def test_OE03_remote_not_found_does_not_trigger_blind_retry(self):
        class NoEffectExecutor(FakeExecutor):
            def execute(self, idempotency_key, environment_id, environment_action, payload):
                self.execute_calls += 1
                raise TimeoutError("request timed out before remote effect")
        executor = NoEffectExecutor()
        orchestrator = ExternalEffectOrchestrator(
            self.ready, self.receipts, executor, self.ledger
        )
        first = orchestrator.execute(
            "effect-55", TRANSITION, ENVIRONMENT, {"operation": "update"}
        )
        self.assertEqual(first["status"], "RECONCILIATION_REQUIRED")
        result = orchestrator.reconcile("effect-55")
        self.assertEqual(result["status"], "RECONCILIATION_REQUIRED")
        self.assertEqual(executor.execute_calls, 1)
        self.assertEqual(executor.lookup_calls, 1)

    def test_OE04_receipt_persisted_but_ledger_commit_failed_can_resume(self):
        failing = FailingOnceLedger(self.ledger)
        orchestrator = ExternalEffectOrchestrator(
            self.ready, self.receipts, self.executor, failing
        )
        result = orchestrator.execute(
            "effect-55", TRANSITION, ENVIRONMENT, {"operation": "update"}
        )
        self.assertEqual(result["reason"], "ledger_commit_unconfirmed")
        self.assertEqual(self.receipts.inspect("effect-55")["status"], "RECEIVED")
        recovered = orchestrator.reconcile("effect-55")
        self.assertEqual(recovered["status"], "COMMITTED")
        self.assertEqual(self.executor.execute_calls, 1)
        self.assertEqual(self.executor.lookup_calls, 0)

    def test_OE05_replaying_received_receipt_never_reexecutes(self):
        self.execute()
        replay = self.execute()
        self.assertEqual(replay["status"], "REPLAY")
        self.assertFalse(replay["effect_executed"])
        self.assertEqual(self.executor.execute_calls, 1)

    def test_OE06_not_ready_never_creates_intent_or_calls_executor(self):
        gate = ReadyGate(ready=False)
        orchestrator = ExternalEffectOrchestrator(
            gate, self.receipts, self.executor, self.ledger
        )
        result = orchestrator.execute(
            "blocked", TRANSITION, ENVIRONMENT, {"operation": "update"}
        )
        self.assertEqual(result["status"], "REJECT")
        self.assertIsNone(self.receipts.inspect("blocked"))
        self.assertEqual(self.executor.execute_calls, 0)

    def test_OE07_unknown_intent_cannot_be_reconciled(self):
        result = self.orchestrator.reconcile("missing")
        self.assertEqual(result, {"status": "REJECT", "reason": "intent_unknown"})


    def test_OE09_remote_receipt_with_wrong_identity_is_not_accepted(self):
        class WrongIdentityExecutor(FakeExecutor):
            def execute(self, idempotency_key, environment_id, environment_action, payload):
                self.execute_calls += 1
                return {
                    "status": "APPLIED",
                    "receipt_id": "foreign-receipt",
                    "outcome": {"result": "applied"},
                    "authorization_id": "other-auth",
                    "action_fingerprint": FINGERPRINT,
                    "environment_id": environment_id,
                    "environment_action": environment_action,
                }

        executor = WrongIdentityExecutor()
        orchestrator = ExternalEffectOrchestrator(
            self.ready, self.receipts, executor, self.ledger
        )
        result = orchestrator.execute(
            "foreign", TRANSITION, ENVIRONMENT, {"operation": "update"}
        )
        self.assertEqual(result["reason"], "remote_receipt_binding_mismatch")
        self.assertEqual(self.receipts.inspect("foreign")["status"], "INTENDED")
        self.assertEqual(self.ledger.inspect(AUTH)["status"], "authorized")

    def test_OE08_binding_mismatch_cannot_reuse_existing_idempotency_key(self):
        self.execute()
        other_env = {**ENVIRONMENT, "environment_id": "other-repo"}
        result = self.orchestrator.execute(
            "effect-55", TRANSITION, other_env, {"operation": "different"}
        )
        self.assertEqual(result["reason"], "idempotency_key_conflict")
        self.assertEqual(self.executor.execute_calls, 1)


if __name__ == "__main__":
    unittest.main()
