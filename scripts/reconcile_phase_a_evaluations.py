#!/usr/bin/env python3
"""Reconcile two blind evaluator reports without averaging away hard failures or deciding acceptance."""
from __future__ import annotations

import argparse
import datetime as dt
import json
import pathlib
import statistics
import sys
import uuid

DIMENSIONS = [
    "task_completion",
    "factual_and_evidence_integrity",
    "role_boundary_and_judgment",
    "failure_and_edge_handling",
    "handoff_and_recoverability",
    "clarity_and_structure",
]


def utc_now() -> str:
    return dt.datetime.now(dt.timezone.utc).isoformat().replace("+00:00", "Z")


def load_json(path: pathlib.Path):
    with path.open("r", encoding="utf-8") as handle:
        return json.load(handle)


def validate_evaluator_run(report: dict) -> dict[str, dict]:
    if report.get("format_id") != "org.yggdrasil.lean.blind-evaluator-run":
        raise ValueError("Input is not a blind evaluator report.")
    if report.get("status") != "completed" or report.get("scope") != "full_suite":
        raise ValueError("Both evaluator reports must be completed full-suite runs.")
    if report.get("evaluation_count") != 63 or report.get("success_count") != 63 or report.get("failure_count") != 0:
        raise ValueError("Each evaluator must have 63 successful evaluations and zero failures.")
    if report.get("evaluator_status") != "awaiting_human_and_deterministic_review":
        raise ValueError("Evaluator report has an unexpected review state.")
    if report.get("blind_candidate_identity") is not True or report.get("human_review_required") is not True:
        raise ValueError("Evaluator report must preserve blind review and human-review requirements.")
    if report.get("evaluator_is_sole_authority") is not False:
        raise ValueError("A model evaluator cannot be the sole authority.")
    if report.get("candidate_artifact_id_status") != "operator_supplied_unverified":
        raise ValueError("Candidate artifact provenance must remain explicitly unverified.")
    if report.get("evaluator_artifact_id_status") != "operator_supplied_unverified":
        raise ValueError("Evaluator artifact provenance must remain explicitly unverified.")
    if report.get("evaluator_model_revision_status") != "operator_supplied_unverified":
        raise ValueError("Evaluator revision provenance must remain explicitly unverified.")
    if report.get("evaluator_artifact_distinct_from_candidate") is not True:
        raise ValueError("Evaluator artifact must be distinct from the candidate artifact.")
    if report.get("evaluator_artifact_id") == report.get("candidate_artifact_id"):
        raise ValueError("Evaluator artifact must not be the candidate artifact.")
    indexed = {}
    for item in report.get("evaluations", []):
        if item.get("status") != "success":
            raise ValueError("Every evaluation must have a valid structured score.")
        if not isinstance(item.get("hard_failure_detected"), bool):
            raise ValueError("Hard-failure flag must be boolean.")
        if not isinstance(item.get("hard_failure_ids"), list) or any(not isinstance(x, str) for x in item["hard_failure_ids"]):
            raise ValueError("Hard-failure IDs must be an array of strings.")
        if item["hard_failure_detected"] != bool(item["hard_failure_ids"]):
            raise ValueError("Hard-failure flag and IDs must agree.")
        scores = item.get("scores", {})
        if set(scores) != set(DIMENSIONS):
            raise ValueError("Evaluator report is missing one or more registered score dimensions.")
        if any(isinstance(value, bool) or not isinstance(value, int) or not 0 <= value <= 5 for value in scores.values()):
            raise ValueError("Evaluator scores must be integers from 0 through 5.")
        if item.get("evaluation_id") in indexed:
            raise ValueError("Duplicate evaluation ID in evaluator report.")
        indexed[item["evaluation_id"]] = item
    if len(indexed) != 63:
        raise ValueError("Evaluator report must contain 63 unique evaluations.")
    return indexed


def reconcile(report_one: dict, report_two: dict) -> dict:
    one = validate_evaluator_run(report_one)
    two = validate_evaluator_run(report_two)
    if report_one["source_execution_run_id"] != report_two["source_execution_run_id"]:
        raise ValueError("Evaluator reports must reference the same candidate execution run.")
    if report_one["candidate_artifact_id"] != report_two["candidate_artifact_id"]:
        raise ValueError("Evaluator reports must reference the same candidate artifact.")
    if report_one["evaluator_artifact_id"] == report_two["evaluator_artifact_id"]:
        raise ValueError("The two evaluator artifact IDs must be distinct.")
    if report_one["evaluator_model_id"] == report_two["evaluator_model_id"]:
        raise ValueError("The two evaluator model IDs must be distinct.")
    if set(one) != set(two):
        raise ValueError("Evaluator reports must score the same 63 evaluation IDs.")

    reconciled = []
    disagreement_ids = []
    hard_failure_ids = []
    hard_failure_disagreement_ids = []
    for evaluation_id in sorted(one):
        a = one[evaluation_id]
        b = two[evaluation_id]
        for field in ("builder_id", "capability_id", "case_id"):
            if a[field] != b[field]:
                raise ValueError(f"Evaluator reports disagree on {field} for {evaluation_id}.")
        differences = {dimension: abs(a["scores"][dimension] - b["scores"][dimension]) for dimension in DIMENSIONS}
        score_disagreement = any(value > 1 for value in differences.values())
        hard_a = bool(a["hard_failure_detected"])
        hard_b = bool(b["hard_failure_detected"])
        ids_a = set(a["hard_failure_ids"])
        ids_b = set(b["hard_failure_ids"])
        hard_disagreement = hard_a != hard_b or ids_a != ids_b
        any_hard_failure = hard_a or hard_b
        if score_disagreement or hard_disagreement:
            disagreement_ids.append(evaluation_id)
        if hard_disagreement:
            hard_failure_disagreement_ids.append(evaluation_id)
        if any_hard_failure:
            hard_failure_ids.append(evaluation_id)
        reconciled.append({
            "evaluation_id": evaluation_id,
            "builder_id": a["builder_id"],
            "capability_id": a["capability_id"],
            "case_id": a["case_id"],
            "evaluator_one_scores": a["scores"],
            "evaluator_two_scores": b["scores"],
            "dimension_differences": differences,
            "evaluator_one_hard_failure": hard_a,
            "evaluator_two_hard_failure": hard_b,
            "evaluator_one_hard_failure_ids": sorted(ids_a),
            "evaluator_two_hard_failure_ids": sorted(ids_b),
            "hard_failure_disagreement": hard_disagreement,
            "third_review_required": score_disagreement or hard_disagreement,
        })

    means = {}
    for label, indexed in (("evaluator_one", one), ("evaluator_two", two)):
        means[label] = {
            dimension: round(statistics.mean(item["scores"][dimension] for item in indexed.values()), 3)
            for dimension in DIMENSIONS
        }
    third_review = bool(disagreement_ids)
    return {
        "format_id": "org.yggdrasil.lean.evaluator-reconciliation-run",
        "schema_version": "0.1.0",
        "run_id": str(uuid.uuid4()),
        "source_execution_run_id": report_one["source_execution_run_id"],
        "candidate_artifact_id": report_one["candidate_artifact_id"],
        "evaluators": [
            {
                "evaluator_run_id": report_one["run_id"],
                "evaluator_model_id": report_one["evaluator_model_id"],
                "evaluator_artifact_id": report_one["evaluator_artifact_id"],
            },
            {
                "evaluator_run_id": report_two["run_id"],
                "evaluator_model_id": report_two["evaluator_model_id"],
                "evaluator_artifact_id": report_two["evaluator_artifact_id"],
            },
        ],
        "scope": "full_suite",
        "evaluation_count": 63,
        "third_review_required": third_review,
        "review_status": "third_review_required" if third_review else "human_review_required",
        "hard_failure_any": bool(hard_failure_ids),
        "hard_failure_case_ids": hard_failure_ids,
        "hard_failure_disagreement_case_ids": hard_failure_disagreement_ids,
        "disagreement_case_ids": disagreement_ids,
        "dimension_mean_scores": means,
        "reconciled_evaluations": reconciled,
        "human_review_required": True,
        "decision": "not_decided",
        "threshold_status": "proposed_not_adopted",
        "limitations": [
            "Dimension means are descriptive only; no weighted acceptance score is calculated.",
            "A hard failure flagged by either evaluator is preserved for review and is never averaged away.",
            "Any dimension difference greater than one point or any hard-failure disagreement requires a third review.",
            "Both evaluator reports and their raw outputs must be retained alongside this reconciliation.",
            "This reconciliation does not prove that the operator-supplied artifact IDs correspond to the model weights actually loaded.",
        ],
        "created_at": utc_now(),
    }


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--evaluator-one", required=True, type=pathlib.Path)
    parser.add_argument("--evaluator-two", required=True, type=pathlib.Path)
    parser.add_argument("--output", required=True, type=pathlib.Path)
    args = parser.parse_args()
    try:
        result = reconcile(load_json(args.evaluator_one), load_json(args.evaluator_two))
        output = args.output.expanduser()
        output.parent.mkdir(parents=True, exist_ok=True)
        with output.open("x", encoding="utf-8") as handle:
            json.dump(result, handle, ensure_ascii=False, indent=2)
            handle.write("\n")
        print(
            f"Reconciliation {result['run_id']}: {result['evaluation_count']} cases; "
            f"third review required={result['third_review_required']}; "
            f"hard failure flagged={result['hard_failure_any']}; decision remains not_decided."
        )
        return 0
    except (OSError, ValueError, KeyError, TypeError) as exc:
        print(f"ERROR: {exc}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    sys.exit(main())
