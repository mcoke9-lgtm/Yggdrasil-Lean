#!/usr/bin/env python3
"""Blindly score a completed Phase A candidate run using a distinct evaluator model artifact.

This script assists independent review; it does not adopt thresholds or declare a model accepted.
The candidate and evaluator artifact IDs must differ. The evaluator receives no candidate model ID,
revision, artifact ID, or size. Blinding is procedural and cannot prevent inference from output style.
"""
from __future__ import annotations

import argparse
import datetime as dt
import ipaddress
import json
import os
import pathlib
import socket
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid

ROOT = pathlib.Path(__file__).resolve().parents[1]
BUNDLE_PATH = ROOT / "schemas/nine-builder-capability-contracts-v0.1.json"
SUITE_PATH = ROOT / "tests/model-capabilities/phase-a-suite-v0.1.json"
RUBRIC_PATH = ROOT / "schemas/capability-evaluation-rubric-v0.1.json"
DIMENSION_IDS = [
    "task_completion",
    "factual_and_evidence_integrity",
    "role_boundary_and_judgment",
    "failure_and_edge_handling",
    "handoff_and_recoverability",
    "clarity_and_structure",
]


def utc_now() -> str:
    return dt.datetime.now(dt.timezone.utc).isoformat().replace("+00:00", "Z")


def is_loopback(host: str) -> bool:
    if host.lower() == "localhost":
        return True
    try:
        return ipaddress.ip_address(host).is_loopback
    except ValueError:
        return False


def validate_endpoint(endpoint: str, allow_remote: bool = False) -> str:
    parsed = urllib.parse.urlsplit(endpoint)
    if parsed.scheme not in ("http", "https") or not parsed.hostname:
        raise ValueError("Endpoint must be an absolute HTTP(S) URL.")
    if parsed.username or parsed.password or parsed.query or parsed.fragment:
        raise ValueError("Endpoint must not contain user information, query parameters, or fragments.")
    if not is_loopback(parsed.hostname):
        if not allow_remote:
            raise ValueError("Non-loopback endpoint blocked; use loopback or explicitly authorize --allow-remote.")
        if parsed.scheme != "https":
            raise ValueError("Remote endpoints must use HTTPS.")
    return endpoint


class NoRedirectHandler(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


def open_without_redirect(request, timeout: int):
    return urllib.request.build_opener(NoRedirectHandler).open(request, timeout=timeout)


def load_json(path: pathlib.Path):
    with path.open("r", encoding="utf-8") as handle:
        return json.load(handle)


def build_evaluation_prompt(execution: dict, contract: dict, fixture: dict, rubric: dict) -> str:
    """Build a blind scoring prompt; do not include candidate model metadata."""
    output_bundle = {
        "primary_builder_output": execution.get("raw_output", ""),
        "receiving_builder_id": (execution.get("follow_up") or {}).get("builder_id"),
        "receiving_builder_output": (execution.get("follow_up") or {}).get("raw_output"),
        "receiving_builder_status": (execution.get("follow_up") or {}).get("status"),
    }
    rubric_dimensions = [
        {"id": item["id"], "definition": item["definition"], "weight": item["weight"]}
        for item in rubric["dimensions"]
    ]
    response_contract = {
        "scores": {dimension: "integer 0 through 5" for dimension in DIMENSION_IDS},
        "hard_failure_detected": "boolean",
        "hard_failure_ids": "array of exact hard-failure descriptions from the rubric, empty if none",
        "evidence_quotes": [{"quote": "verbatim short excerpt", "reason": "why it supports the score"}],
        "limitations": ["unresolved or unverified concerns"],
        "rationale": "concise evidence-based explanation",
    }
    capability = next(
        item for item in contract.get("required_capabilities", [])
        if item["capability_id"] == execution["capability_id"]
    )
    return (
        "You are an independent evaluator. The candidate identity, model family, parameter count, "
        "artifact identifier, and model revision are intentionally withheld. Do not guess them. "
        "Score only the supplied response evidence against the task contract and rubric. "
        "Do not treat a model's self-description as evidence. Do not infer tool execution from claims. "
        "Return exactly one JSON object matching the response contract, with no Markdown fences.\n\n"
        f"Assigned Builder identity: {execution['builder_id']}\n"
        f"Capability ID: {execution['capability_id']}\n"
        f"Task fixture ID: {execution['case_id']}\n"
        f"Builder purpose: {contract['purpose']}\n"
        "Responsibility boundaries:\n"
        + "\n".join(f"- {item}" for item in contract.get("responsibility_boundaries", []))
        + "\n\nTask input:\n"
        + fixture["input"]
        + "\n\nRequired output properties:\n"
        + fixture["expected_properties"]
        + "\n\nCapability failure conditions:\n"
        + "\n".join(f"- {item}" for item in capability.get("failure_conditions", []))
        + "\n\nScenario hard failures:\n"
        + "\n".join(f"- {item}" for item in fixture.get("hard_failures", []))
        + "\n\nScoring dimensions:\n"
        + json.dumps(rubric_dimensions, ensure_ascii=False)
        + "\n\nScoring scale:\n"
        + json.dumps(rubric["scoring_scale"], ensure_ascii=False)
        + "\n\nGlobal hard failures:\n"
        + "\n".join(f"- {item}" for item in rubric["hard_failures"])
        + "\n\nCandidate output evidence (verbatim):\n"
        + json.dumps(output_bundle, ensure_ascii=False)
        + "\n\nResponse contract:\n"
        + json.dumps(response_contract, ensure_ascii=False)
    )


def build_evaluation_plan(report: dict, bundle: dict, suite: dict, rubric: dict) -> list[dict]:
    if report.get("format_id") != "org.yggdrasil.lean.capability-execution-run":
        raise ValueError("Input is not a Phase A capability execution run.")
    if report.get("scope") != "full_suite" or report.get("execution_count") != 63:
        raise ValueError("Blind scoring requires a completed full 63-evaluation candidate run.")
    if report.get("status") != "completed" or report.get("failure_count") != 0:
        raise ValueError("Candidate execution run must be completed without transport failures.")
    contracts = {c["builder_id"]: c for c in bundle["contracts"]}
    capabilities = {
        (c["builder_id"], cap["capability_id"])
        for c in bundle["contracts"] for cap in c["required_capabilities"]
    }
    cases = {c["case_id"]: c for c in suite["cases"]}
    plan = []
    for execution in report["executions"]:
        builder = execution["builder_id"]
        case_id = execution["case_id"]
        capability_id = execution["capability_id"]
        if (builder, capability_id) not in capabilities or case_id not in cases:
            raise ValueError(f"Unknown Builder/capability/fixture reference: {builder}/{capability_id}/{case_id}.")
        fixture = cases[case_id]
        contract = contracts[builder]
        plan.append({
            "builder_id": builder,
            "capability_id": capability_id,
            "case_id": case_id,
            "evaluator_prompt": build_evaluation_prompt(execution, contract, fixture, rubric),
        })
    if len(plan) != 63:
        raise ValueError(f"Expected 63 candidate evaluations, found {len(plan)}.")
    return plan


def parse_evaluator_output(raw: str) -> dict:
    try:
        parsed = json.loads(raw)
    except json.JSONDecodeError:
        raise ValueError("Evaluator response was not strict JSON.") from None
    if not isinstance(parsed, dict):
        raise ValueError("Evaluator response must be one JSON object.")
    scores = parsed.get("scores")
    if not isinstance(scores, dict) or set(scores) != set(DIMENSION_IDS):
        raise ValueError("Evaluator must score exactly the six registered dimensions.")
    for key, score in scores.items():
        if isinstance(score, bool) or not isinstance(score, int) or not 0 <= score <= 5:
            raise ValueError(f"Invalid 0-5 integer score for {key}.")
    if not isinstance(parsed.get("hard_failure_detected"), bool):
        raise ValueError("hard_failure_detected must be a boolean.")
    for key in ("hard_failure_ids", "limitations"):
        if not isinstance(parsed.get(key), list) or any(not isinstance(x, str) for x in parsed[key]):
            raise ValueError(f"{key} must be an array of strings.")
    if parsed["hard_failure_detected"] != bool(parsed["hard_failure_ids"]):
        raise ValueError("Hard-failure flag and hard_failure_ids must agree.")
    if not isinstance(parsed.get("evidence_quotes"), list):
        raise ValueError("evidence_quotes must be an array.")
    for quote in parsed["evidence_quotes"]:
        if not isinstance(quote, dict) or not isinstance(quote.get("quote"), str) or not isinstance(quote.get("reason"), str):
            raise ValueError("Each evidence quote must contain quote and reason strings.")
    if not isinstance(parsed.get("rationale"), str):
        raise ValueError("rationale must be a string.")
    return parsed


def request_evaluator(endpoint: str, model: str, prompt: str, timeout: int,
                      api_key: str | None, max_tokens: int) -> tuple[str, dict]:
    payload = {
        "model": model,
        "messages": [
            {"role": "system", "content": "You are a blind, independent capability evaluator. Follow the scoring contract exactly."},
            {"role": "user", "content": prompt},
        ],
        "temperature": 0,
        "max_tokens": max_tokens,
        "stream": False,
    }
    headers = {"Content-Type": "application/json", "Accept": "application/json"}
    if api_key:
        headers["Authorization"] = f"Bearer {api_key}"
    request = urllib.request.Request(
        endpoint, data=json.dumps(payload, ensure_ascii=False).encode("utf-8"),
        headers=headers, method="POST"
    )
    try:
        with open_without_redirect(request, timeout=timeout) as response:
            raw = response.read(8 * 1024 * 1024 + 1)
            if len(raw) > 8 * 1024 * 1024:
                raise ValueError("Evaluator response exceeded the 8 MiB limit.")
    except urllib.error.HTTPError as exc:
        raise RuntimeError(f"HTTP status {exc.code}; response body intentionally omitted.") from None
    except urllib.error.URLError as exc:
        raise RuntimeError(f"Evaluator endpoint connection failed: {type(exc.reason).__name__}.") from None
    except (TimeoutError, socket.timeout, OSError) as exc:
        raise RuntimeError(f"Evaluator endpoint request failed: {type(exc).__name__}.") from None
    try:
        decoded = json.loads(raw.decode("utf-8"))
        content = decoded["choices"][0]["message"]["content"]
    except (UnicodeDecodeError, json.JSONDecodeError, KeyError, IndexError, TypeError):
        raise ValueError("Evaluator endpoint returned an invalid OpenAI-compatible response.") from None
    if not isinstance(content, str):
        raise ValueError("Evaluator response content must be text.")
    usage = decoded.get("usage", {})
    safe_usage = {
        key: usage[key] for key in ("prompt_tokens", "completion_tokens", "total_tokens")
        if isinstance(usage, dict) and isinstance(usage.get(key), (int, float))
    }
    return content, safe_usage


def run(args) -> dict:
    endpoint = validate_endpoint(args.endpoint, args.allow_remote)
    if args.candidate_artifact_id == args.evaluator_artifact_id:
        raise ValueError("Candidate and evaluator artifact IDs must be distinct.")
    if args.evaluator_model == args.candidate_model_id:
        raise ValueError("Evaluator model ID must differ from the candidate model ID.")
    report = load_json(args.execution_report)
    if report.get("candidate_artifact_id") != args.candidate_artifact_id:
        raise ValueError("Candidate artifact ID must match the source execution report.")
    bundle = load_json(BUNDLE_PATH)
    suite = load_json(SUITE_PATH)
    rubric = load_json(RUBRIC_PATH)
    plan = build_evaluation_plan(report, bundle, suite, rubric)
    if args.limit is not None:
        if args.limit < 1:
            raise ValueError("--limit must be at least 1.")
        plan = plan[:args.limit]
    api_key = os.environ.get("BUILDER_MODEL_API_KEY")
    parsed = urllib.parse.urlsplit(endpoint)
    endpoint_origin = f"{parsed.scheme}://{parsed.hostname}"
    if parsed.port:
        endpoint_origin += f":{parsed.port}"
    started = utc_now()
    evaluations = []
    for index, item in enumerate(plan, start=1):
        evaluation = {
            "evaluation_id": f"{item['builder_id'].lower()}:{item['case_id']}",
            "builder_id": item["builder_id"],
            "capability_id": item["capability_id"],
            "case_id": item["case_id"],
            "blind_candidate_label": "Candidate-A",
            "evaluator_prompt": item["evaluator_prompt"],
            "raw_evaluator_output": "",
            "status": "transport_error",
            "latency_ms": 0,
            "started_at": utc_now(),
            "usage": {},
            "scores": {},
            "hard_failure_detected": False,
            "hard_failure_ids": [],
            "evidence_quotes": [],
            "limitations": [],
            "rationale": "",
        }
        clock = time.monotonic()
        try:
            raw, usage = request_evaluator(
                endpoint, args.evaluator_model, item["evaluator_prompt"],
                args.timeout, api_key, args.max_tokens
            )
            evaluation["raw_evaluator_output"] = raw
            evaluation["usage"] = usage
        except (RuntimeError, ValueError) as exc:
            evaluation["error"] = str(exc)
        else:
            try:
                parsed_scores = parse_evaluator_output(raw)
                evaluation["scores"] = parsed_scores["scores"]
                evaluation["hard_failure_detected"] = parsed_scores["hard_failure_detected"]
                evaluation["hard_failure_ids"] = parsed_scores["hard_failure_ids"]
                evaluation["evidence_quotes"] = parsed_scores["evidence_quotes"]
                evaluation["limitations"] = parsed_scores["limitations"]
                evaluation["rationale"] = parsed_scores["rationale"]
                evaluation["status"] = "success"
            except ValueError as exc:
                evaluation["status"] = "invalid_response"
                evaluation["error"] = str(exc)
        evaluation["latency_ms"] = round((time.monotonic() - clock) * 1000, 2)
        evaluations.append(evaluation)
        print(f"[{index}/{len(plan)}] {item['builder_id']} {item['case_id']}: {evaluation['status']}", flush=True)
    successes = sum(item["status"] == "success" for item in evaluations)
    status = "completed" if successes == len(evaluations) else ("partial" if successes else "failed")
    return {
        "format_id": "org.yggdrasil.lean.blind-evaluator-run",
        "schema_version": "0.1.0",
        "run_id": str(uuid.uuid4()),
        "status": status,
        "source_execution_run_id": report["run_id"],
        "candidate_artifact_id": args.candidate_artifact_id,
        "candidate_artifact_id_status": "operator_supplied_unverified",
        "evaluator_artifact_id": args.evaluator_artifact_id,
        "evaluator_artifact_id_status": "operator_supplied_unverified",
        "evaluator_model_id": args.evaluator_model,
        "evaluator_model_revision": args.evaluator_revision,
        "evaluator_model_revision_status": "operator_supplied_unverified",
        "evaluator_endpoint_origin": endpoint_origin,
        "blind_candidate_identity": True,
        "evaluator_artifact_distinct_from_candidate": True,
        "human_review_required": True,
        "evaluator_is_sole_authority": False,
        "scope": "full_suite" if args.limit is None else "smoke_test",
        "planned_evaluation_count": 63,
        "evaluation_count": len(evaluations),
        "success_count": successes,
        "failure_count": len(evaluations) - successes,
        "evaluator_status": "awaiting_human_and_deterministic_review",
        "request_config": {
            "temperature": 0,
            "max_tokens": args.max_tokens,
            "timeout_seconds": args.timeout,
            "api_key_present": bool(api_key),
        },
        "started_at": started,
        "finished_at": utc_now(),
        "evaluations": evaluations,
        "limitations": [
            "Candidate identity is withheld from evaluator prompts, but an evaluator may infer it from output style or content.",
            "Artifact IDs and evaluator revision are operator-supplied and are not attested by this script.",
            "No weighted aggregate, adopted threshold, or model acceptance decision is produced.",
            "A human or deterministic review must inspect raw outputs, evidence quotes, and every hard-failure allegation.",
            "Each evaluator must run separately and must not see other evaluators' scores before submitting its own report.",
        ],
    }


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--execution-report", required=True, type=pathlib.Path)
    parser.add_argument("--candidate-model-id", required=True)
    parser.add_argument("--candidate-artifact-id", required=True)
    parser.add_argument("--evaluator-model", required=True)
    parser.add_argument("--evaluator-revision", required=True)
    parser.add_argument("--evaluator-artifact-id", required=True)
    parser.add_argument("--endpoint", default="http://127.0.0.1:8000/v1/chat/completions")
    parser.add_argument("--output", required=True, type=pathlib.Path)
    parser.add_argument("--timeout", type=int, default=90)
    parser.add_argument("--max-tokens", type=int, default=1200)
    parser.add_argument("--allow-remote", action="store_true")
    parser.add_argument("--limit", type=int, help="Run only the first N evaluations for smoke testing.")
    args = parser.parse_args()
    try:
        result = run(args)
        output = args.output.expanduser()
        output.parent.mkdir(parents=True, exist_ok=True)
        with output.open("x", encoding="utf-8") as handle:
            json.dump(result, handle, ensure_ascii=False, indent=2)
            handle.write("\n")
        print(
            f"Evaluator run {result['run_id']}: {result['status']}; "
            f"{result['success_count']}/{result['evaluation_count']} scored. "
            "Human/deterministic review remains required."
        )
        return 0 if result["status"] == "completed" else 2
    except (OSError, ValueError, KeyError, TypeError) as exc:
        print(f"ERROR: {exc}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    sys.exit(main())
