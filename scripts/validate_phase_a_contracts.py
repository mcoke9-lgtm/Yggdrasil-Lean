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

EXPECTED_BUILDERS = {
    "HUGINN", "MUNINN", "KVASIR", "MARI", "HODR",
    "TYR", "LOKI", "ODIN", "BALDR",
}
SHARED_CASES = {
    "case.shr-evid-01", "case.shr-ask-02",
    "case.shr-hand-03", "case.shr-rec-04",
}


def load(path: Path):
    with path.open("r", encoding="utf-8") as handle:
        return json.load(handle)


def schema_errors(validator, instance, label):
    errors = sorted(validator.iter_errors(instance), key=lambda e: list(map(str, e.absolute_path)))
    for error in errors:
        location = "/".join(map(str, error.absolute_path)) or "<root>"
        print(f"FAIL {label} at {location}: {error.message}")
    return len(errors)


def main() -> int:
    schema = load(SCHEMA_PATH)
    bundle = load(BUNDLE_PATH)
    suite = load(SUITE_PATH)
    rubric = load(RUBRIC_PATH)

    Draft202012Validator.check_schema(schema)
    validator = Draft202012Validator(schema)

    failures = 0
    failures += schema_errors(validator, bundle, "capability bundle")
    failures += schema_errors(validator, suite, "evaluation suite")
    failures += schema_errors(validator, rubric, "evaluation rubric")

    contracts = bundle.get("contracts", [])
    cases = suite.get("cases", [])
    builder_ids = [contract.get("builder_id") for contract in contracts]
    case_ids = [case.get("case_id") for case in cases]
    case_id_set = set(case_ids)

    if set(builder_ids) != EXPECTED_BUILDERS or len(builder_ids) != len(EXPECTED_BUILDERS):
        print("FAIL builder identity set must contain each of the nine expected IDs exactly once")
        failures += 1
    if len(cases) != 22 or len(case_ids) != len(case_id_set):
        print("FAIL evaluation suite must contain 22 uniquely identified cases")
        failures += 1
    if sum(case.get("test_kind") == "role_specific" for case in cases) != 18:
        print("FAIL evaluation suite must contain 18 role-specific cases")
        failures += 1
    if sum(case.get("test_kind") == "shared" for case in cases) != 4:
        print("FAIL evaluation suite must contain four shared cases")
        failures += 1

    fixture_by_id = {case.get("case_id"): case for case in cases}
    for contract in contracts:
        builder = contract.get("builder_id", "<unknown>")
        capabilities = contract.get("required_capabilities", [])
        if len(capabilities) != 6:
            print(f"FAIL {builder} must define exactly six required capability tests")
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
        if role_case_count != 2 or shared_case_count != 4:
            print(f"FAIL {builder} must reference two role-specific and four shared fixtures")
            failures += 1

    for fixture in cases:
        if fixture.get("test_kind") == "shared" and set(fixture.get("builder_ids", [])) != EXPECTED_BUILDERS:
            print(f"FAIL shared fixture {fixture.get('case_id')} must target all nine Builders")
            failures += 1
        if fixture.get("test_kind") == "role_specific" and fixture.get("builder_id") not in EXPECTED_BUILDERS:
            print(f"FAIL role-specific fixture {fixture.get('case_id')} has an unknown Builder")
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
    print("PASS: Capability bundle, evaluation suite, and scoring rubric satisfy the JSON Schema.")
    print("PASS: Six evaluation dimensions have normalized weights.")
    print("PASS: Nine unique Builder contracts; six capability tests each.")
    print("PASS: 22 unique fixtures (18 role-specific, four shared).")
    print("PASS: All capability-to-fixture references resolve.")
    print("PASS: Provisional thresholds and unexecuted fixture states remain explicit.")
    print("NOTE: This validates contract data only; it does not evaluate any model.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
