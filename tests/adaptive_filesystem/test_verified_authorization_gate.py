"""Contract tests for canonical action binding and the verifier boundary."""
import unittest

from adaptive_filesystem.action_fingerprint import action_fingerprint
from adaptive_filesystem.verified_authorization_gate import VerifiedAuthorizationGate


class FakeLedger:
    def __init__(self, fail=False):
        self.registered = []
        self.fail = fail

    def register_verified_authorization(self, authorization_id, fingerprint):
        if self.fail:
            raise OSError("simulated persistence failure")
        self.registered.append((authorization_id, fingerprint))


def request(**overrides):
    value = {
        "authorization_id": "auth-42",
        "actor": "executor",
        "source_branch": "Roots",
        "source_checkpoint": "ckpt-7",
        "target_branch": "Trunk",
        "scope": ["advance:Roots->Trunk"],
    }
    value.update(overrides)
    if "action_fingerprint" not in overrides:
        try:
            value["action_fingerprint"] = action_fingerprint(value)
        except ValueError:
            value["action_fingerprint"] = None
    return value


def verified(record):
    return {
        "status": "VERIFIED",
        "authorization_id": record["authorization_id"],
        "action_fingerprint": record["action_fingerprint"],
    }


class VerifiedAuthorizationGateTests(unittest.TestCase):
    def test_VG01_exact_verified_record_is_registered(self):
        record = request()
        ledger = FakeLedger()
        result = VerifiedAuthorizationGate(verified, ledger).authorize(record)
        self.assertEqual(result["status"], "REGISTERED")
        self.assertEqual(ledger.registered, [
            ("auth-42", action_fingerprint(record))
        ])

    def test_VG02_missing_request_field_fails_before_verifier(self):
        called = []
        record = request(source_checkpoint=None)
        gate = VerifiedAuthorizationGate(
            lambda value: called.append(value) or verified(value), FakeLedger()
        )
        result = gate.authorize(record)
        self.assertEqual(result["reason"], "action_description_invalid")
        self.assertEqual(called, [])

    def test_VG03_unverified_decision_is_not_registered(self):
        ledger = FakeLedger()
        result = VerifiedAuthorizationGate(
            lambda value: {"status": "DENIED"}, ledger
        ).authorize(request())
        self.assertEqual(result["reason"], "authority_not_verified")
        self.assertEqual(ledger.registered, [])

    def test_VG04_wrong_authorization_identity_is_not_registered(self):
        ledger = FakeLedger()
        result = VerifiedAuthorizationGate(
            lambda value: {**verified(value), "authorization_id": "other"}, ledger
        ).authorize(request())
        self.assertEqual(result["reason"], "authorization_id_mismatch")
        self.assertEqual(ledger.registered, [])

    def test_VG05_wrong_action_binding_is_not_registered(self):
        ledger = FakeLedger()
        result = VerifiedAuthorizationGate(
            lambda value: {
                **verified(value), "action_fingerprint": "sha256:wrong"
            },
            ledger,
        ).authorize(request())
        self.assertEqual(result["reason"], "action_fingerprint_mismatch")
        self.assertEqual(ledger.registered, [])

    def test_VG06_verifier_exception_fails_closed(self):
        def broken_verifier(value):
            raise RuntimeError("verifier unavailable")

        ledger = FakeLedger()
        result = VerifiedAuthorizationGate(broken_verifier, ledger).authorize(
            request()
        )
        self.assertEqual(result["reason"], "verifier_error")
        self.assertEqual(ledger.registered, [])

    def test_VG07_malformed_verifier_response_fails_closed(self):
        ledger = FakeLedger()
        result = VerifiedAuthorizationGate(lambda value: True, ledger).authorize(
            request()
        )
        self.assertEqual(result["reason"], "verifier_response_invalid")
        self.assertEqual(ledger.registered, [])

    def test_VG08_ledger_registration_failure_fails_closed(self):
        result = VerifiedAuthorizationGate(verified, FakeLedger(fail=True)).authorize(
            request()
        )
        self.assertEqual(result["reason"], "ledger_registration_failed")

    def test_VG09_gate_does_not_execute_external_action(self):
        ledger = FakeLedger()
        effects = []
        result = VerifiedAuthorizationGate(verified, ledger).authorize(request())
        self.assertEqual(result["status"], "REGISTERED")
        self.assertEqual(effects, [])
        self.assertEqual(len(ledger.registered), 1)

    def test_VG10_caller_cannot_supply_a_forged_fingerprint(self):
        ledger = FakeLedger()
        record = request(action_fingerprint="sha256:forged")
        called = []
        gate = VerifiedAuthorizationGate(
            lambda value: called.append(value) or verified(value), ledger
        )
        result = gate.authorize(record)
        self.assertEqual(result["reason"], "caller_fingerprint_mismatch")
        self.assertEqual(called, [])
        self.assertEqual(ledger.registered, [])

    def test_VG11_scope_order_does_not_change_fingerprint(self):
        first = request(scope=["read", "advance"])
        second = request(scope=["advance", "read"])
        self.assertEqual(
            first["action_fingerprint"], second["action_fingerprint"]
        )


if __name__ == "__main__":
    unittest.main()
