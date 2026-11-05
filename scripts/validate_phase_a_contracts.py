#!/usr/bin/env python3
"""Validate Phase A model lifecycle schemas, contracts, fixtures, and cross-links."""
from __future__ import annotations

import json
import sys
from pathlib import Path

from jsonschema import Draft202012Validator

ROOT = Path(__file__).resolve().parents[1]
SCHEMA_PATH = ROOT / "schemas/model-lifecycle-contracts-1.0.schema.json"
BUNDLE_PATH = ROOT / "schemas/nine-builder-capability-contracts-v0.1.json"
SUITE_PATH = ROOT / "tests/model-capabilities/phase-a-suite-v0.1.json"
RUBRIC_PATH = ROOT / "schemas/capability-evaluation-rubric-v0.1.json"
STUDY_PATH = ROOT / "schemas/model-reduction-study-v0.1.json"
INVENTORY_PATH = ROOT / "schemas/model-family-candidate-inventory-v0.1.json"

EXPECTED_BUILDERS = {
    "HUGINN", "MUNINN", "KVASIR", "MARI", "HODR",
    "TYR", "LOKI", "ODIN", "BALDR",
}
SHARED_CASES = {
    "case.shr-evid-01", "case.shr-ask-02",
    "case.shr-hand-03", "case.shr-rec-04", "case.shr-role-05",
}


def load(path: Path):
    with path.open("r", encoding="utf-8") as handle:
        return json.load(handle)


def schema_errors(validator, instance, label):
    errors = sorted(validator.iter_errors(instance), key=lambda e: list(map(str, e.absolute_path)))
    for error in errors:
        location = "/".join(map(str, error.absolute_path)) or "<root>"
        print(f"FAIL {label} at {location}: {error.message}")
        if error.validator == "oneOf":
            for nested in error.context:
                nested_location = "/".join(map(str, nested.absolute_path)) or "<root>"
                print(f"  DETAIL {label} at {nested_location}: {nested.message}")
    return len(errors)


def main() -> int:
    schema = load(SCHEMA_PATH)
    bundle = load(BUNDLE_PATH)
    suite = load(SUITE_PATH)
    rubric = load(RUBRIC_PATH)
    study = load(STUDY_PATH)
    inventory = load(INVENTORY_PATH)

    Draft202012Validator.check_schema(schema)
    validator = Draft202012Validator(schema)

    failures = 0
    failures += schema_errors(validator, bundle, "capability bundle")
    failures += schema_errors(validator, suite, "evaluation suite")
    failures += schema_errors(validator, rubric, "evaluation rubric")
    failures += schema_errors(validator, study, "model reduction study")
    failures += schema_errors(validator, inventory, "model family inventory")

    contracts = bundle.get("contracts", [])
    cases = suite.get("cases", [])
    builder_ids = [contract.get("builder_id") for contract in contracts]
    case_ids = [case.get("case_id") for case in cases]
    case_id_set = set(case_ids)

    if set(builder_ids) != EXPECTED_BUILDERS or len(builder_ids) != len(EXPECTED_BUILDERS):
        print("FAIL builder identity set must contain each of the nine expected IDs exactly once")
        failures += 1
    if len(cases) != 23 or len(case_ids) != len(case_id_set):
        print("FAIL evaluation suite must contain 23 uniquely identified cases")
        failures += 1
    if sum(case.get("test_kind") == "role_specific" for case in cases) != 18:
        print("FAIL evaluation suite must contain 18 role-specific cases")
        failures += 1
    if sum(case.get("test_kind") == "shared" for case in cases) != 5:
        print("FAIL evaluation suite must contain five shared cases")
        failures += 1

    fixture_by_id = {case.get("case_id"): case for case in cases}
    for contract in contracts:
        builder = contract.get("builder_id", "<unknown>")
        capabilities = contract.get("required_capabilities", [])
        if len(capabilities) != 7:
            print(f"FAIL {builder} must define exactly seven required capability tests")
            failures += 1
        capability_ids = [capability.get("capability_id") for capability in capabilities]
        if len(capability_ids) != len(set(capability_ids)):
            print(f"FAIL {builder} has duplicate capability IDs")
            failures += 1
        role_case_count = 0
        shared_case_count = 0
        for capability in capabilities:
            refs = capability.get("test_case_refs", [])
            if len(refs) != 1 or refs[0].get("ref") not in case_id_set:
                print(f"FAIL {builder}/{capability.get('capability_id')} must reference one existing fixture")
                failures += 1
                continue
            fixture = fixture_by_id[refs[0]["ref"]]
            if fixture.get("capability_id") != capability.get("capability_id"):
                print(f"FAIL {builder}/{capability.get('capability_id')} references a different capability fixture")
                failures += 1
            if fixture.get("test_kind") == "role_specific":
                role_case_count += 1
                if fixture.get("builder_id") != builder:
                    print(f"FAIL {builder}/{capability.get('capability_id')} references another Builder's fixture")
                    failures += 1
            elif fixture.get("test_kind") == "shared":
                shared_case_count += 1
                if builder not in fixture.get("builder_ids", []):
                    print(f"FAIL shared fixture {fixture.get('case_id')} omits {builder}")
                    failures += 1
        if role_case_count != 2 or shared_case_count != 5:
            print(f"FAIL {builder} must reference two role-specific and five shared fixtures")
            failures += 1

    for fixture in cases:
        if fixture.get("test_kind") == "shared" and set(fixture.get("builder_ids", [])) != EXPECTED_BUILDERS:
            print(f"FAIL shared fixture {fixture.get('case_id')} must target all nine Builders")
            failures += 1
        if fixture.get("test_kind") == "role_specific" and fixture.get("builder_id") not in EXPECTED_BUILDERS:
            print(f"FAIL role-specific fixture {fixture.get('case_id')} has an unknown Builder")
            failures += 1

    expected_contract_refs = {f"contract.{builder.lower()}.v0_1" for builder in EXPECTED_BUILDERS}
    if set(study.get("builder_identity_refs", [])) != EXPECTED_BUILDERS:
        print("FAIL model reduction study must cover all nine Builder identities")
        failures += 1
    if set(study.get("capability_contract_refs", [])) != expected_contract_refs:
        print("FAIL model reduction study must reference all nine Builder capability contracts")
        failures += 1
    if study.get("evaluation_suite_ref") != "suite.nine_builder.v0_1":
        print("FAIL model reduction study must reference the current capability suite")
        failures += 1
    if study.get("evaluation_rubric_ref") != "rubric.capability.v0_1":
        print("FAIL model reduction study must reference the current evaluation rubric")
        failures += 1
    evaluation_design = study.get("evaluation_design", {})
    if set(evaluation_design.get("evaluator_builder_ids", [])) & set(evaluation_design.get("independent_verifier_builder_ids", [])):
        print("FAIL analytical evaluator and independent verifier roles must be distinct")
        failures += 1
    proposed_evaluators = set(evaluation_design.get("proposed_evaluator_model_family_refs", []))
    if evaluation_design.get("evaluator_selection_status") != "family_shortlist" or proposed_evaluators != {"qwen3_5_2b", "smollm3_3b"}:
        print("FAIL Qwen3.5-2B and SmolLM3-3B must remain a proposed evaluator-family shortlist")
        failures += 1
    if evaluation_design.get("evaluator_model_artifact_refs") != []:
        print("FAIL no evaluator model artifact may be selected before exact artifact registration")
        failures += 1
    if evaluation_design.get("blind_candidate_identity_until_initial_scoring") is not True:
        print("FAIL initial evaluator scoring must be blind to candidate identity")
        failures += 1
    if evaluation_design.get("evaluator_models_must_not_see_other_scores_before_submission") is not True:
        print("FAIL evaluator models must submit independent scores before seeing other evaluator scores")
        failures += 1
        print("FAIL no independent evaluator artifact may be implied before it is registered")
        failures += 1
    if evaluation_design.get("builder_roles_are_independent_model_artifacts") is not False:
        print("FAIL Builder role prompts must not be misrepresented as independent model artifacts")
        failures += 1
    expansion = study.get("expansion_blueprint", {})
    if expansion.get("status") != "planned" or expansion.get("reconstruction_claim") != "not_selected":
        print("FAIL expansion blueprint must remain planned until dependencies and recovery evidence are registered")
        failures += 1
    if expansion.get("student_weights_alone_sufficient") is not False:
        print("FAIL student weights alone must not be represented as sufficient to reconstruct a larger model")
        failures += 1
    if evaluation_design.get("model_self_evaluation_is_sufficient") is not False:
        print("FAIL candidate model must not be its own sole evaluator")
        failures += 1
    if study.get("status") != "planned" or study.get("decision") != "not_decided":
        print("FAIL model reduction study must remain planned without an acceptance decision")
        failures += 1
    inventory_ids = {candidate.get("model_family_id") for candidate in inventory.get("candidates", [])}
    study_ids = {candidate.get("model_family_ref") for candidate in study.get("source_candidates", [])}
    if len(inventory_ids) != 5 or study_ids != inventory_ids:
        print("FAIL the five research-stage model families must match the study candidate shortlist")
        failures += 1
    if any(candidate.get("baseline_evaluation_status") != "not_evaluated" or candidate.get("artifact_refs") for candidate in study.get("source_candidates", [])):
        print("FAIL research candidates must not imply registered artifacts or completed baseline evaluation")
        failures += 1
    if rubric.get("aggregation", {}).get("threshold_status") != "proposed_not_adopted":
        print("FAIL evaluation thresholds must remain explicitly unadopted")
        failures += 1
    weights = [dimension.get("weight", 0) for dimension in rubric.get("dimensions", [])]
    if len(weights) != 6 or abs(sum(weights) - 1.0) > 1e-9:
        print("FAIL six rubric dimension weights must sum to exactly 1.0")
        failures += 1
    if bundle.get("threshold_policy", {}).get("status") != "proposed_not_adopted":
        print("FAIL provisional thresholds must remain explicitly unadopted")
        failures += 1
    if any(case.get("status") != "proposed_not_executed" for case in cases):
        print("FAIL proposed fixtures must not be represented as executed")
        failures += 1

    if failures:
        print(f"Phase A validation failed: {failures} issue(s)")
        return 1

    print("PASS: Draft 2020-12 schema is valid.")
    print("PASS: Capability bundle, evaluation suite, rubric, model reduction study, and model-family inventory satisfy the JSON Schema.")
    print("PASS: Five model families are research candidates only; no artifacts or baseline results are claimed.")
    print("PASS: Six evaluation dimensions have normalized weights.")
    print("PASS: Nine unique Builder contracts; seven capability tests each.")
    print("PASS: 23 unique fixtures (18 role-specific, five shared).")
    print("PASS: All capability-to-fixture references resolve.")
    print("PASS: Provisional thresholds and unexecuted fixture states remain explicit.")
    print("NOTE: This validates contract data only; it does not evaluate any model.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
