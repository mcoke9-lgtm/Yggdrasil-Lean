"""Synthetic FIRA transition-authorization contract tests.

Scope: executable policy fixture only; NOT a production controller, trusted
signature verifier, durable ledger, or cross-platform transaction system.

Run:
    python -m unittest discover -s tests/adaptive_filesystem -p 'test_*.py' -v

The six TA assertions keep transition permission, environment-action
permission, execution evidence, and single-use consumption separate.
"""
from copy import deepcopy
import unittest


def authorization_fixture(**overrides):
    record = {
        "authorization_id": "auth-001",
        "issuer": "branch-owner",
        "delegate": "executor",
        "source_branch": "Roots",
        "source_checkpoint": "roots-ckpt-7",
        "target_branch": "Trunk",
        "scope": ["advance:Roots->Trunk"],
        "evidence_ref": "evidence-001",
        "evidence_verifiable": True,
        "status": "authorized",
        "consumed": False,
    }
    record.update(overrides)
    return record


def action_permission_fixture(**overrides):
    permission = {
        "permission_id": "env-001",
        "actor": "executor",
        "environment": "Trunk",
        "actions": ["read"],
        "evidence_ref": "environment-policy-001",
        "evidence_verifiable": True,
        "status": "authorized",
    }
    permission.update(overrides)
    return permission


def authorize_transition(authorization, *, actor, source_branch,
                         source_checkpoint, target_branch):
    """Return a decision; this function does not mutate branch state."""
    if not isinstance(authorization, dict):
        return {"status": "REJECT", "reason": "authorization_missing"}
    required = (
        authorization.get("authorization_id"),
        authorization.get("issuer"),
        authorization.get("delegate"),
        authorization.get("source_branch"),
        authorization.get("source_checkpoint"),
        authorization.get("target_branch"),
        authorization.get("evidence_ref"),
    )
    if not all(required):
        return {"status": "REJECT", "reason": "required_field_missing"}
    if authorization.get("status") != "authorized":
        return {"status": "REJECT", "reason": "not_authorized"}
    if authorization.get("evidence_verifiable") is not True:
        return {"status": "REJECT", "reason": "evidence_unverifiable"}
    if authorization.get("consumed") is True:
        return {"status": "REJECT", "reason": "already_consumed"}
    if authorization["delegate"] != actor:
        return {"status": "REJECT", "reason": "delegate_mismatch"}
    if authorization["source_branch"] != source_branch:
        return {"status": "REJECT", "reason": "source_branch_mismatch"}
    if authorization["source_checkpoint"] != source_checkpoint:
        return {"status": "REJECT", "reason": "source_checkpoint_mismatch"}
    if authorization["target_branch"] != target_branch:
        return {"status": "REJECT", "reason": "target_branch_mismatch"}
    transition = f"advance:{source_branch}->{target_branch}"
    if transition not in authorization.get("scope", []):
        return {"status": "REJECT", "reason": "transition_out_of_scope"}
    return {"status": "ALLOW", "reason": "transition_authorized"}


def authorize_environment_action(permission, *, actor, environment, action):
    """Check action permission independently of transition authorization."""
    if not isinstance(permission, dict):
        return {"status": "REJECT", "reason": "environment_permission_missing"}
    required = (
        permission.get("permission_id"),
        permission.get("actor"),
        permission.get("environment"),
        permission.get("evidence_ref"),
    )
    if not all(required):
        return {"status": "REJECT", "reason": "required_field_missing"}
    if permission.get("status") != "authorized":
        return {"status": "REJECT", "reason": "not_authorized"}
    if permission.get("evidence_verifiable") is not True:
        return {"status": "REJECT", "reason": "evidence_unverifiable"}
    if permission["actor"] != actor:
        return {"status": "REJECT", "reason": "actor_mismatch"}
    if permission["environment"] != environment:
        return {"status": "REJECT", "reason": "environment_mismatch"}
    if action not in permission.get("actions", []):
        return {"status": "REJECT", "reason": "action_out_of_scope"}
    return {"status": "ALLOW", "reason": "environment_action_authorized"}


class TransitionAuthorizationContractTests(unittest.TestCase):
    def test_TA01_no_explicit_transition_authorization_leaves_state_unchanged(self):
        before = {"branch": "Roots", "checkpoint": "roots-ckpt-7"}
        after = deepcopy(before)
        decision = authorize_transition(
            None, actor="executor", source_branch="Roots",
            source_checkpoint="roots-ckpt-7", target_branch="Trunk"
        )
        self.assertEqual(decision["status"], "REJECT")
        self.assertEqual(after, before)

    def test_TA02_transition_authorization_does_not_grant_environment_action(self):
        transition = authorization_fixture()
        decision = authorize_environment_action(
            transition, actor="executor", environment="Trunk", action="write"
        )
        self.assertEqual(decision["status"], "REJECT")

    def test_TA03_environment_permission_does_not_authorize_branch_transition(self):
        environment_permission = action_permission_fixture(
            actions=["read", "write"]
        )
        decision = authorize_transition(
            environment_permission, actor="executor",
            source_branch="Roots", source_checkpoint="roots-ckpt-7",
            target_branch="Trunk"
        )
        self.assertEqual(decision["status"], "REJECT")

    def test_TA04_permission_for_action_a_cannot_authorize_action_b(self):
        permission = action_permission_fixture(actions=["read"])
        allowed = authorize_environment_action(
            permission, actor="executor", environment="Trunk", action="read"
        )
        denied = authorize_environment_action(
            permission, actor="executor", environment="Trunk", action="delete"
        )
        self.assertEqual(allowed["status"], "ALLOW")
        self.assertEqual(denied, {
            "status": "REJECT", "reason": "action_out_of_scope"
        })

    def test_TA05_consumed_authorization_cannot_commit_a_second_transition(self):
        authorization = authorization_fixture(consumed=True)
        decision = authorize_transition(
            authorization, actor="executor", source_branch="Roots",
            source_checkpoint="roots-ckpt-7", target_branch="Trunk"
        )
        self.assertEqual(decision, {
            "status": "REJECT", "reason": "already_consumed"
        })

    def test_TA06_missing_or_unverifiable_evidence_fails_closed(self):
        missing = authorization_fixture(evidence_ref=None)
        unverifiable = authorization_fixture(evidence_verifiable=False)
        for authorization in (missing, unverifiable):
            with self.subTest(authorization=authorization):
                decision = authorize_transition(
                    authorization, actor="executor", source_branch="Roots",
                    source_checkpoint="roots-ckpt-7", target_branch="Trunk"
                )
                self.assertEqual(decision["status"], "REJECT")


if __name__ == "__main__":
    unittest.main()
