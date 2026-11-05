"""Unit tests for two-evaluator reconciliation and hard-failure preservation."""
from __future__ import annotations

import json
import jsonschema
import pathlib
import sys
import unittest

ROOT = pathlib.Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "scripts"))
import reconcile_phase_a_evaluations as reconciler


def make_report(model_id: str, artifact_id: str, run_id: str, score: int = 4) -> dict:
    dimensions = reconciler.DIMENSIONS
    evaluations = []
    for index in range(63):
        builder = ["HUGINN", "MUNINN", "KVASIR", "MARI", "HODR", "TYR", "LOKI", "ODIN", "BALDR"][index % 9]
        evaluations.append({
            "evaluation_id": f"{builder.lower()}:case-{index:02d}",
            "builder_id": builder,
            "capability_id": f"CAP-{index:02d}",
            "case_id": f"case-{index:02d}",
            "status": "success",
            "scores": {dimension: score for dimension in dimensions},
            "hard_failure_detected": False,
            "hard_failure_ids": [],
        })
    return {
        "format_id": "org.yggdrasil.lean.blind-evaluator-run",
        "schema_version": "0.1.0",
        "run_id": run_id,
        "status": "completed",
        "source_execution_run_id": "candidate-run-001",
        "candidate_artifact_id": "artifact.candidate",
        "evaluator_artifact_id": artifact_id,
        "evaluator_model_id": model_id,
        "scope": "full_suite",
        "evaluation_count": 63,
        "success_count": 63,
        "failure_count": 0,
        "evaluator_status": "awaiting_human_and_deterministic_review",
        "blind_candidate_identity": True,
        "human_review_required": True,
        "evaluator_is_sole_authority": False,
        "evaluations": evaluations,
    }


class ReconciliationTests(unittest.TestCase):
    def setUp(self):
        self.one = make_report("judge-one", "artifact.judge.one", "judge-run-001", 4)
        self.two = make_report("judge-two", "artifact.judge.two", "judge-run-002", 4)

    def test_agreement_keeps_human_review_and_no_acceptance_decision(self):
        result = reconciler.reconcile(self.one, self.two)
        schema = reconciler.load_json(ROOT / "schemas/model-lifecycle-contracts-1.0.schema.json")
        jsonschema.validate(result, schema)
        self.assertFalse(result["third_review_required"])
        self.assertFalse(result["hard_failure_any"])
        self.assertTrue(result["human_review_required"])
        self.assertEqual("not_decided", result["decision"])
        self.assertEqual("proposed_not_adopted", result["threshold_status"])
        self.assertEqual(63, result["evaluation_count"])

    def test_dimension_difference_over_one_requires_third_review(self):
        self.two["evaluations"][0]["scores"]["task_completion"] = 2
        result = reconciler.reconcile(self.one, self.two)
        self.assertTrue(result["third_review_required"])
        self.assertIn(self.one["evaluations"][0]["evaluation_id"], result["disagreement_case_ids"])

    def test_hard_failure_from_either_evaluator_is_never_averaged_away(self):
        self.one["evaluations"][3]["hard_failure_detected"] = True
        self.one["evaluations"][3]["hard_failure_ids"] = ["Invented evidence"]
        result = reconciler.reconcile(self.one, self.two)
        self.assertTrue(result["hard_failure_any"])
        self.assertIn(self.one["evaluations"][3]["evaluation_id"], result["hard_failure_case_ids"])
        self.assertTrue(result["third_review_required"])

    def test_hard_failure_id_disagreement_requires_third_review(self):
        self.one["evaluations"][4]["hard_failure_detected"] = True
        self.one["evaluations"][4]["hard_failure_ids"] = ["Invented evidence"]
        self.two["evaluations"][4]["hard_failure_detected"] = True
        self.two["evaluations"][4]["hard_failure_ids"] = ["Unauthorized action"]
        result = reconciler.reconcile(self.one, self.two)
        self.assertTrue(result["third_review_required"])
        self.assertIn(self.one["evaluations"][4]["evaluation_id"], result["hard_failure_disagreement_case_ids"])

    def test_same_evaluator_artifact_is_rejected(self):
        self.two["evaluator_artifact_id"] = self.one["evaluator_artifact_id"]
        with self.assertRaisesRegex(ValueError, "artifact IDs must be distinct"):
            reconciler.reconcile(self.one, self.two)

    def test_same_candidate_execution_run_is_required(self):
        self.two["source_execution_run_id"] = "different-run"
        with self.assertRaisesRegex(ValueError, "same candidate execution run"):
            reconciler.reconcile(self.one, self.two)

    def test_invalid_or_incomplete_evaluator_report_is_rejected(self):
        self.two["evaluations"].pop()
        with self.assertRaises(ValueError):
            reconciler.reconcile(self.one, self.two)


if __name__ == "__main__":
    unittest.main()
