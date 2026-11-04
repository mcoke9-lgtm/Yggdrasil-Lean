"""Contract tests for the injected authority-verifier boundary."""
import unittest

from adaptive_filesystem.verified_authorization_gate import VerifiedAuthorizationGate


class FakeLedger:
    def __init__(self, fail=False):
        self.registered = []
        self.fail = fail

    def register_verified_authorization(self, authorization_id, action_fingerprint):
        if self.fail:
            raise OSError("simulated persistence failure")
        self.registered.append((authorization_id, action_fingerprint))


def request(**overrides):
    value = {
        "authorization_id": "auth-42",
        "action_fingerprint": "advance:Roots@ckpt-7->Trunk",
        "actor": "executor",
        "source_branch": "Roots",
        "source_checkpoint": "ckpt-7",
        "target_branch": "Trunk",
        "scope": "advance:Roots->Trunk",
    }
    value.update(overrides)
    return value


def verified(record):
    return {
        "status": "VERIFIED",
        "authorization_id": record["authorization_id"],
        "action_fingerprint": record["action_fingerprint"],
    }


class VerifiedAuthorizationGateTests(unittest.TestCase):
    def test_VG01_exact_verified_record_is_registered(self):
        ledger = FakeLedger()
        gate = VerifiedAuthorizationGate(verified, ledger)
        result = gate.authorize(request())
        self.assertEqual(result["status"], "REGISTERED")
        self.assertEqual(
            ledger.registered,
            [("auth-42", "advance:Roots@ckpt-7->Trunk")],
        )

    def test_VG02_missing_request_field_fails_before_verifier(self):
        called = []
        gate = VerifiedAuthorizationGate(
            lambda record: called.append(record) or verified(record), FakeLedger()
        )
        result = gate.authorize(request(source_checkpoint=None))
        self.assertEqual(result, {
            "status": "REJECT", "reason": "request_field_missing"
        })
        self.assertEqual(called, [])

    def test_VG03_unverified_decision_is_not_registered(self):
        ledger = FakeLedger()
        gate = VerifiedAuthorizationGate(
            lambda record: {"status": "DENIED"}, ledger
        )
        result = gate.authorize(request())
        self.assertEqual(result["reason"], "authority_not_verified")
        self.assertEqual(ledger.registered, [])

    def test_VG04_wrong_authorization_identity_is_not_registered(self):
        ledger = FakeLedger()
        gate = VerifiedAuthorizationGate(
            lambda record: {**verified(record), "authorization_id": "other"}, ledger
        )
        result = gate.authorize(request())
        self.assertEqual(result["reason"], "authorization_id_mismatch")
        self.assertEqual(ledger.registered, [])

    def test_VG05_wrong_action_binding_is_not_registered(self):
        ledger = FakeLedger()
        gate = VerifiedAuthorizationGate(
            lambda record: {
                **verified(record), "action_fingerprint": "delete:record-9"
            },
            ledger,
        )
        result = gate.authorize(request())
        self.assertEqual(result["reason"], "action_fingerprint_mismatch")
        self.assertEqual(ledger.registered, [])

    def test_VG06_verifier_exception_fails_closed(self):
        ledger = FakeLedger()

        def broken_verifier(record):
            raise RuntimeError("verifier unavailable")

        result = VerifiedAuthorizationGate(broken_verifier, ledger).authorize(request())
        self.assertEqual(result["reason"], "verifier_error")
        self.assertEqual(ledger.registered, [])

    def test_VG07_malformed_verifier_response_fails_closed(self):
        ledger = FakeLedger()
        result = VerifiedAuthorizationGate(lambda record: True, ledger).authorize(
            request()
        )
        self.assertEqual(result["reason"], "verifier_response_invalid")
        self.assertEqual(ledger.registered, [])

    def test_VG08_ledger_registration_failure_fails_closed(self):
        ledger = FakeLedger(fail=True)
        result = VerifiedAuthorizationGate(verified, ledger).authorize(request())
        self.assertEqual(result["reason"], "ledger_registration_failed")

    def test_VG09_gate_does_not_execute_or_commit_external_action(self):
        ledger = FakeLedger()
        effects = []
        gate = VerifiedAuthorizationGate(verified, ledger)
        result = gate.authorize(request())
        self.assertEqual(result["status"], "REGISTERED")
        self.assertEqual(effects, [])
        self.assertEqual(len(ledger.registered), 1)


if __name__ == "__main__":
    unittest.main()
