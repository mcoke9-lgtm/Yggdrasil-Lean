"""Tests keeping environment permission separate from transition authority."""
import unittest

from adaptive_filesystem.environment_permission_gate import (
    EnvironmentPermissionGate,
    ExecutionReadinessGate,
)


def env_request(**overrides):
    value = {
        "authorization_id": "auth-42",
        "action_fingerprint": "sha256:abc",
        "actor": "executor",
        "environment_id": "repo-prod",
        "environment_action": "write:branch-ref",
    }
    value.update(overrides)
    return value


def permitted(record):
    return {"status": "PERMITTED", **record}


class FakeTransitionGate:
    def __init__(self, result=None):
        self.result = result or {
            "status": "REGISTERED",
            "authorization_id": "auth-42",
            "action_fingerprint": "sha256:abc",
        }
        self.calls = 0

    def authorize(self, request):
        self.calls += 1
        return self.result


class EnvironmentPermissionGateTests(unittest.TestCase):
    def test_EP01_exact_independent_permission_is_accepted(self):
        request = env_request()
        result = EnvironmentPermissionGate(permitted).check(request)
        self.assertEqual(result["status"], "PERMITTED")

    def test_EP02_missing_environment_binding_fails_closed(self):
        request = env_request(environment_id="")
        called = []
        result = EnvironmentPermissionGate(
            lambda value: called.append(value) or permitted(value)
        ).check(request)
        self.assertEqual(result["reason"], "request_field_missing")
        self.assertEqual(called, [])

    def test_EP03_denial_is_not_overridden_by_transition_authority(self):
        result = EnvironmentPermissionGate(
            lambda value: {"status": "DENIED"}
        ).check(env_request())
        self.assertEqual(result["reason"], "environment_permission_denied")

    def test_EP04_provider_exception_fails_closed(self):
        def broken(value):
            raise RuntimeError("policy unavailable")

        result = EnvironmentPermissionGate(broken).check(env_request())
        self.assertEqual(result["reason"], "permission_provider_error")

    def test_EP05_wrong_environment_binding_is_rejected(self):
        result = EnvironmentPermissionGate(
            lambda value: {**permitted(value), "environment_id": "other"}
        ).check(env_request())
        self.assertEqual(result["reason"], "environment_id_mismatch")

    def test_EP06_wrong_action_binding_is_rejected(self):
        result = EnvironmentPermissionGate(
            lambda value: {**permitted(value), "environment_action": "delete:repo"}
        ).check(env_request())
        self.assertEqual(result["reason"], "environment_action_mismatch")

    def test_EP07_malformed_provider_response_is_rejected(self):
        result = EnvironmentPermissionGate(lambda value: True).check(env_request())
        self.assertEqual(result["reason"], "permission_response_invalid")

    def test_ER08_both_independent_gates_are_required(self):
        transition = FakeTransitionGate()
        readiness = ExecutionReadinessGate(
            transition,
            EnvironmentPermissionGate(permitted),
        )
        result = readiness.check({}, env_request())
        self.assertEqual(result["status"], "READY")
        self.assertFalse(result["effect_executed"])

    def test_ER09_environment_denial_blocks_readiness(self):
        readiness = ExecutionReadinessGate(
            FakeTransitionGate(),
            EnvironmentPermissionGate(lambda value: {"status": "DENIED"}),
        )
        result = readiness.check({}, env_request())
        self.assertEqual(result["reason"], "environment_permission_rejected")

    def test_ER10_transition_denial_prevents_environment_check(self):
        transition = FakeTransitionGate({"status": "REJECT"})
        environment_calls = []
        readiness = ExecutionReadinessGate(
            transition,
            EnvironmentPermissionGate(
                lambda value: environment_calls.append(value) or permitted(value)
            ),
        )
        result = readiness.check({}, env_request())
        self.assertEqual(result["reason"], "transition_authorization_rejected")
        self.assertEqual(environment_calls, [])

    def test_ER11_mismatched_authorization_cannot_be_combined(self):
        transition = FakeTransitionGate({
            "status": "REGISTERED",
            "authorization_id": "auth-42",
            "action_fingerprint": "sha256:abc",
        })
        readiness = ExecutionReadinessGate(
            transition,
            EnvironmentPermissionGate(
                lambda value: {**permitted(value), "authorization_id": "other"}
            ),
        )
        result = readiness.check({}, env_request())
        self.assertEqual(result["reason"], "environment_permission_rejected")

    def test_ER12_readiness_never_executes_effect(self):
        effects = []
        readiness = ExecutionReadinessGate(
            FakeTransitionGate(),
            EnvironmentPermissionGate(permitted),
        )
        result = readiness.check({}, env_request())
        self.assertEqual(result["status"], "READY")
        self.assertEqual(effects, [])


if __name__ == "__main__":
    unittest.main()
