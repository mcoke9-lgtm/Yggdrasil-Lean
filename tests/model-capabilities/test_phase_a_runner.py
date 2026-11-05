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

    @patch("run_phase_a_model_evaluation.urllib.request.urlopen")
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

    @patch("run_phase_a_model_evaluation.urllib.request.urlopen")
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
