"""Unit tests for blind independent Phase A model evaluation."""
from __future__ import annotations

import json
import jsonschema
import pathlib
import sys
import unittest
import tempfile
from types import SimpleNamespace
from unittest.mock import patch

ROOT = pathlib.Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "scripts"))
import evaluate_phase_a_run as evaluator
import run_phase_a_model_evaluation as candidate_runner


class FakeResponse:
    def __init__(self, payload: dict):
        self.payload = json.dumps(payload).encode("utf-8")

    def __enter__(self):
        return self

    def __exit__(self, *_args):
        return False

    def read(self, _limit=-1):
        return self.payload


class BlindEvaluatorTests(unittest.TestCase):
    def setUp(self):
        self.bundle = evaluator.load_json(ROOT / "schemas/nine-builder-capability-contracts-v0.1.json")
        self.suite = evaluator.load_json(ROOT / "tests/model-capabilities/phase-a-suite-v0.1.json")
        self.rubric = evaluator.load_json(ROOT / "schemas/capability-evaluation-rubric-v0.1.json")
        self.plan = candidate_runner.build_evaluation_plan(self.bundle, self.suite)
        self.execution_report = {
            "format_id": "org.yggdrasil.lean.capability-execution-run",
            "run_id": "candidate-run-001",
            "candidate_artifact_id": "artifact.candidate",
            "candidate_artifact_id_status": "operator_supplied_unverified",
            "candidate_model_id": "candidate-model",
            "candidate_revision_status": "operator_supplied_unverified",
            "status": "completed",
            "scope": "full_suite",
            "execution_count": 63,
            "failure_count": 0,
            "executions": [],
        }
        for item in self.plan:
            self.execution_report["executions"].append({
                "builder_id": item["builder_id"],
                "capability_id": item["capability_id"],
                "case_id": item["case_id"],
                "raw_output": "Observed output: preserve state and report unknowns.",
                "follow_up": {
                    "builder_id": item["follow_up_builder_id"],
                    "raw_output": "Receiver confirms handoff state.",
                    "status": "success",
                } if item.get("follow_up_builder_id") else None,
            })

    def test_plan_contains_63_blind_evaluations(self):
        plan = evaluator.build_evaluation_plan(self.execution_report, self.bundle, self.suite, self.rubric)
        self.assertEqual(63, len(plan))
        self.assertEqual(9, sum("Receiver confirms handoff state." in item["evaluator_prompt"] for item in plan))
        self.assertNotIn("qwen3", plan[0]["evaluator_prompt"].lower())
        self.assertNotIn("candidate-artifact-secret", plan[0]["evaluator_prompt"])

    def test_handoff_prompt_includes_sender_and_receiver_outputs(self):
        plan = evaluator.build_evaluation_plan(self.execution_report, self.bundle, self.suite, self.rubric)
        handoff = next(item for item in plan if item["case_id"] == "case.shr-hand-03")
        self.assertIn("Observed output", handoff["evaluator_prompt"])
        self.assertIn("Receiver confirms handoff state.", handoff["evaluator_prompt"])
        self.assertIn("MUNINN", handoff["evaluator_prompt"])

    def test_rejects_partial_candidate_execution_report(self):
        report = dict(self.execution_report)
        report["scope"] = "smoke_test"
        with self.assertRaises(ValueError):
            evaluator.build_evaluation_plan(report, self.bundle, self.suite, self.rubric)

    def test_evaluator_must_have_a_distinct_artifact(self):
        args = SimpleNamespace(
            endpoint="http://127.0.0.1:8000/v1/chat/completions",
            allow_remote=False,
            candidate_artifact_id="artifact.same",
            evaluator_artifact_id="artifact.same",
            candidate_model_id="candidate",
            evaluator_model="evaluator",
            execution_report=ROOT / "unused.json",
            evaluator_revision="rev",
            output=ROOT / "unused-output.json",
            timeout=10,
            max_tokens=128,
            limit=1,
        )
        with self.assertRaisesRegex(ValueError, "must be distinct"):
            evaluator.run(args)

    def test_evaluator_model_id_must_differ_from_candidate_model_id(self):
        args = SimpleNamespace(
            endpoint="http://127.0.0.1:8000/v1/chat/completions",
            allow_remote=False,
            candidate_artifact_id="artifact.candidate",
            evaluator_artifact_id="artifact.evaluator",
            candidate_model_id="same-model",
            evaluator_model="same-model",
            execution_report=ROOT / "unused.json",
            evaluator_revision="rev",
            output=ROOT / "unused-output.json",
            timeout=10,
            max_tokens=128,
            limit=1,
        )
        with self.assertRaisesRegex(ValueError, "must differ"):
            evaluator.run(args)

    @patch("evaluate_phase_a_run.request_evaluator")
    def test_run_binds_candidate_and_evaluator_artifacts_and_requires_human_review(self, mock_request):
        valid = {
            "scores": {key: 4 for key in evaluator.DIMENSION_IDS},
            "hard_failure_detected": False,
            "hard_failure_ids": [],
            "evidence_quotes": [],
            "limitations": ["No hardware telemetry."],
            "rationale": "The output meets the fixture's required properties.",
        }
        mock_request.return_value = (json.dumps(valid), {})
        with tempfile.TemporaryDirectory() as temp_dir:
            report_path = pathlib.Path(temp_dir) / "candidate-run.json"
            report_path.write_text(json.dumps(self.execution_report), encoding="utf-8")
            args = SimpleNamespace(
                endpoint="http://127.0.0.1:8000/v1/chat/completions",
                allow_remote=False,
                candidate_artifact_id="artifact.candidate",
                evaluator_artifact_id="artifact.judge",
                candidate_model_id="candidate-model",
                evaluator_model="judge-model",
                execution_report=report_path,
                evaluator_revision="judge-revision",
                output=pathlib.Path(temp_dir) / "evaluator-run.json",
                timeout=10,
                max_tokens=128,
                limit=1,
            )
            result = evaluator.run(args)
        self.assertEqual("smoke_test", result["scope"])
        self.assertTrue(result["blind_candidate_identity"])
        self.assertTrue(result["human_review_required"])
        self.assertFalse(result["evaluator_is_sole_authority"])
        self.assertEqual("artifact.candidate", result["candidate_artifact_id"])
        self.assertEqual("artifact.judge", result["evaluator_artifact_id"])
        schema = evaluator.load_json(ROOT / "schemas/model-lifecycle-contracts-1.0.schema.json")
        jsonschema.validate(result, schema)
        self.assertEqual("success", result["evaluations"][0]["status"])

    def test_parse_scores_requires_exact_six_dimensions(self):
        payload = {
            "scores": {key: 4 for key in evaluator.DIMENSION_IDS},
            "hard_failure_detected": False,
            "hard_failure_ids": [],
            "evidence_quotes": [{"quote": "preserve state", "reason": "shows handoff continuity"}],
            "limitations": ["No live execution evidence."],
            "rationale": "The response preserves the stated task state.",
        }
        parsed = evaluator.parse_evaluator_output(json.dumps(payload))
        self.assertEqual(6, len(parsed["scores"]))
        payload["scores"]["extra"] = 5
        with self.assertRaises(ValueError):
            evaluator.parse_evaluator_output(json.dumps(payload))

    def test_parse_scores_rejects_markdown_fenced_json(self):
        payload = {"scores": {}, "hard_failure_detected": False}
        fence = chr(96) * 3
        with self.assertRaises(ValueError):
            evaluator.parse_evaluator_output(fence + "json\n" + json.dumps(payload) + "\n" + fence)

    def test_endpoint_policy_is_loopback_first_and_no_redirect(self):
        self.assertTrue(evaluator.validate_endpoint("http://127.0.0.1:8000/v1/chat/completions"))
        with self.assertRaises(ValueError):
            evaluator.validate_endpoint("https://evaluator.example/v1/chat/completions")
        self.assertTrue(evaluator.validate_endpoint("https://evaluator.example/v1/chat/completions", True))
        self.assertIsNone(evaluator.NoRedirectHandler().redirect_request(None, None, 302, "Found", {}, "https://other.example/"))

    @patch("evaluate_phase_a_run.open_without_redirect")
    def test_evaluator_response_parser_and_token_usage(self, mock_open):
        valid = {
            "scores": {key: 4 for key in evaluator.DIMENSION_IDS},
            "hard_failure_detected": False,
            "hard_failure_ids": [],
            "evidence_quotes": [],
            "limitations": [],
            "rationale": "All required properties are present.",
        }
        mock_open.return_value = FakeResponse({
            "choices": [{"message": {"content": json.dumps(valid)}}],
            "usage": {"prompt_tokens": 200, "completion_tokens": 80, "total_tokens": 280},
        })
        raw, usage = evaluator.request_evaluator(
            "http://127.0.0.1:8000/v1/chat/completions", "judge", "prompt", 10, None, 1200
        )
        self.assertEqual(valid, evaluator.parse_evaluator_output(raw))
        self.assertEqual(280, usage["total_tokens"])


if __name__ == "__main__":
    unittest.main()
