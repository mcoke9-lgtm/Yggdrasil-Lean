#!/usr/bin/env python3
"""Validate Phase A model lifecycle schemas, contracts, fixtures, and cross-links."""
from __future__ import annotations

import json
import sys
from pathlib import Path

from jsonschema import Draft202012Validator

ROOT = Path(__file__).resolve().parents[2]
SCHEMA_PATH = ROOT / "schemas/model-lifecycle-contracts-1.0.schema.json"
BUNDLE_PATH = ROOT / "schemas/nine-builder-capability-contracts-v0.1.json"
SUITE_PATH = ROOT / "tests/model-capabilities/phase-a-suite-v0.1.json"

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

    Draft202012Validator.check_schema(schema)
    validator = Draft202012Validator(schema)

    failures = 0
    failures += schema_errors(validator, bundle, "capability bundle")
    failures += schema_errors(validator, suite, "evaluation suite")

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

    for contract in contracts:
        builder = contract.get("builder_id", "<unknown>")
        capabilities = contract.get("required_capabilities", [])
        if len(capabilities) != 6:
            print(f"FAIL {builder} must define exactly six required capability tests")
            failures += 1
        for capability in capabilities:
            refs = capability.get("test_case_refs", [])
            if len(refs) != 1 or refs[0].get("ref") not in case_id_set:
                print(f"FAIL {builder}/{capability.get('capability_id')} must reference one existing fixture")
                failures += 1
            for shared_id in SHARED_CASES:
                if shared_id in {item.get("ref") for item in refs}:
                    pass
        referenced_shared = {
            item.get("ref")
            for capability in capabilities
            for item in capability.get("test_case_refs", [])
        }
        if not SHARED_CASES.issubset(referenced_shared):
            print(f"FAIL {builder} does not reference all four shared capability fixtures")
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
    print("PASS: Capability bundle and evaluation suite satisfy the JSON Schema.")
    print("PASS: Nine unique Builder contracts; six capability tests each.")
    print("PASS: 22 unique fixtures (18 role-specific, four shared).")
    print("PASS: All capability-to-fixture references resolve.")
    print("PASS: Provisional thresholds and unexecuted fixture states remain explicit.")
    print("NOTE: This validates contract data only; it does not evaluate any model.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
