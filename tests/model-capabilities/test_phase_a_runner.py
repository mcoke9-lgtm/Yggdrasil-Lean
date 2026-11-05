"""Unit tests for the Phase A local model evaluation runner."""
from __future__ import annotations

import json
import pathlib
import sys
import unittest
from unittest.mock import patch

ROOT = pathlib.Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "scripts"))
import run_phase_a_model_evaluation as runner


class FakeResponse:
    def __init__(self, payload: dict):
        self.payload = json.dumps(payload).encode("utf-8")

    def __enter__(self):
        return self

    def __exit__(self, *_args):
        return False

    def read(self, _limit=-1):
        return self.payload


class PhaseARunnerTests(unittest.TestCase):
    def setUp(self):
        self.bundle = runner.load_json(ROOT / "schemas/nine-builder-capability-contracts-v0.1.json")
        self.suite = runner.load_json(ROOT / "tests/model-capabilities/phase-a-suite-v0.1.json")

    def test_plan_contains_exactly_63_role_mapped_executions(self):
        plan = runner.build_evaluation_plan(self.bundle, self.suite)
        self.assertEqual(63, len(plan))
        self.assertEqual(9, sum(bool(item.get("follow_up_builder_id")) for item in plan))
        for builder in runner.BUILDERS:
            own = [item for item in plan if item["builder_id"] == builder]
            self.assertEqual(7, len(own))
            self.assertEqual(2, sum(item["case_id"].startswith("case."+builder[:3].lower()) for item in own))
            self.assertEqual(5, sum(item["case_id"].startswith("case.shr-") for item in own))

    def test_shared_role_conflict_is_injected_without_changing_assignment(self):
        plan = runner.build_evaluation_plan(self.bundle, self.suite)
        cases = [item for item in plan if item["case_id"] == "case.shr-role-05"]
        self.assertEqual(9, len(cases))
        for item in cases:
            self.assertIn("untrusted content", item["user_prompt"])
            self.assertIn(item["builder_id"], item["system_prompt"])
            self.assertIn("I am ", item["user_prompt"])

    def test_handoff_receiver_turn_is_a_separate_builder_identity(self):
        bundle = self.bundle
        plan = runner.build_evaluation_plan(bundle, self.suite)
        sender = next(item for item in plan if item["builder_id"] == "HUGINN" and item["case_id"] == "case.shr-hand-03")
        follow = runner.build_handoff_follow_up(bundle, sender, "Completed: schema checked. Failed: restore test. Unknown: host RAM.")
        self.assertEqual("MUNINN", follow["builder_id"])
        self.assertIn("MUNINN", follow["system_prompt"])
        self.assertIn("HUGINN", follow["user_prompt"])
        self.assertIn("Completed: schema checked. Failed: restore test. Unknown: host RAM.", follow["user_prompt"])
        self.assertIn("untrusted task-state", follow["user_prompt"])

    @patch("run_phase_a_model_evaluation.request_model")
    def test_handoff_execution_runs_receiver_and_counts_both_calls(self, mock_request):
        from types import SimpleNamespace
        mock_request.return_value = ("handoff or continuation output", {})
        args = SimpleNamespace(
            endpoint="http://127.0.0.1:8000/v1/chat/completions",
            allow_remote=False,
            model="mock-model",
            artifact_id="artifact.mock-model",
            revision="mock-revision",
            output=ROOT / "unused.json",
            timeout=10,
            max_tokens=128,
            limit=5,
        )
        report = runner.run(args)
        self.assertEqual(5, report["execution_count"])
        self.assertEqual(6, report["model_call_count"])
        self.assertEqual(6, mock_request.call_count)
        self.assertEqual("success", report["executions"][4]["status"])
        self.assertEqual("MUNINN", report["executions"][4]["follow_up"]["builder_id"])
        self.assertEqual("smoke_test", report["scope"])
        self.assertEqual("operator_supplied_unverified", report["candidate_revision_status"])

    def test_endpoint_defaults_to_loopback(self):
        self.assertEqual(
            "http://127.0.0.1:8000/v1/chat/completions",
            runner.validate_endpoint("http://127.0.0.1:8000/v1/chat/completions")
        )

    def test_remote_http_is_rejected_even_with_remote_flag(self):
        with self.assertRaises(ValueError):
            runner.validate_endpoint("http://192.168.1.20:8000/v1/chat/completions", True)

    def test_remote_https_requires_explicit_flag(self):
        with self.assertRaises(ValueError):
            runner.validate_endpoint("https://models.example/v1/chat/completions")
        self.assertTrue(runner.validate_endpoint("https://models.example/v1/chat/completions", True))

    def test_endpoint_rejects_userinfo_query_and_fragment(self):
        for endpoint in (
            "http://user:pass@127.0.0.1:8000/v1/chat/completions",
            "http://127.0.0.1:8000/v1/chat/completions?token=x",
            "http://127.0.0.1:8000/v1/chat/completions#fragment",
        ):
            with self.subTest(endpoint=endpoint):
                with self.assertRaises(ValueError):
                    runner.validate_endpoint(endpoint)

    @patch("run_phase_a_model_evaluation.open_without_redirect")
    def test_openai_compatible_response_is_parsed_and_usage_is_retained(self, mock_urlopen):
        mock_urlopen.return_value = FakeResponse({
            "choices": [{"message": {"content": "Evidence: observed; unknown: none."}}],
            "usage": {"prompt_tokens": 12, "completion_tokens": 8, "total_tokens": 20},
        })
        output, usage = runner.request_model(
            "http://127.0.0.1:8000/v1/chat/completions",
            "test-model", "system", "user", 10, "not-logged", 128
        )
        self.assertEqual("Evidence: observed; unknown: none.", output)
        self.assertEqual({"prompt_tokens": 12, "completion_tokens": 8, "total_tokens": 20}, usage)
        request = mock_urlopen.call_args.args[0]
        self.assertEqual("Bearer not-logged", request.get_header("Authorization"))
        self.assertNotIn("not-logged", request.full_url)

    @patch("run_phase_a_model_evaluation.open_without_redirect")
    def test_http_error_body_is_not_recorded(self, mock_urlopen):
        mock_urlopen.side_effect = runner.urllib.error.HTTPError(
            "http://127.0.0.1", 401, "Unauthorized", {}, None
        )
        with self.assertRaisesRegex(RuntimeError, "HTTP status 401; response body intentionally omitted"):
            runner.request_model(
                "http://127.0.0.1:8000/v1/chat/completions",
                "test-model", "system", "user", 10, None, 128
            )


if __name__ == "__main__":
    unittest.main()
