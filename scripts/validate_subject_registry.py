#!/usr/bin/env python3
"""Validate the Nine Builders subject registry and its cross-record invariants."""
from __future__ import annotations

import json
from pathlib import Path

from jsonschema import Draft202012Validator

ROOT = Path(__file__).resolve().parents[1]
SCHEMA_PATH = ROOT / "schemas/subject-registry-v1.schema.json"
REGISTRY_PATH = ROOT / "subject-registry/registry.json"
EXPECTED_BUILDERS = {
    "HUGINN", "MUNINN", "KVASIR", "MARI", "HODR",
    "TYR", "LOKI", "ODIN", "BALDR",
}


def load(path: Path):
    with path.open("r", encoding="utf-8") as handle:
        return json.load(handle)


def validate_registry(root: Path = ROOT) -> list[str]:
    schema = load(root / "schemas/subject-registry-v1.schema.json")
    registry = load(root / "subject-registry/registry.json")
    Draft202012Validator.check_schema(schema)
    errors = []
    for error in Draft202012Validator(schema).iter_errors(registry):
        location = "/".join(map(str, error.absolute_path)) or "<root>"
        errors.append(f"schema error at {location}: {error.message}")

    subjects = registry.get("subjects", [])
    subject_ids = [item.get("subject_id") for item in subjects]
    if len(subject_ids) != len(set(subject_ids)):
        errors.append("subject IDs must be unique")

    for subject in subjects:
        label = subject.get("subject_id", "<unknown>")
        stewardship = subject.get("stewardship", [])
        builder_ids = [item.get("builder_id") for item in stewardship]
        if len(builder_ids) != 9 or set(builder_ids) != EXPECTED_BUILDERS:
            errors.append(f"{label}: subject stewardship must contain each Builder exactly once")

        subheadings = subject.get("subheadings", [])
        subheading_ids = [item.get("subheading_id") for item in subheadings]
        if len(subheading_ids) != len(set(subheading_ids)):
            errors.append(f"{label}: subheading IDs must be unique")
        for subheading in subheadings:
            if not subheading.get("recognition_terms"):
                errors.append(f"{label}/{subheading.get('subheading_id')}: recognition terms are required")
            unknown = set(subheading.get("primary_stewards", [])) - EXPECTED_BUILDERS
            if unknown:
                errors.append(
                    f"{label}/{subheading.get('subheading_id')}: unknown stewards {sorted(unknown)}"
                )

        protocol_ref = subject.get("conversation_protocol_ref", "").split("#", 1)[0]
        protocol_path = root / protocol_ref
        if not protocol_path.is_file():
            errors.append(f"{label}: conversation protocol reference does not resolve: {protocol_ref}")
        elif "**Protocol version:** 2" not in protocol_path.read_text(encoding="utf-8"):
            errors.append(f"{label}: referenced conversation protocol is not version 2")

    return errors


def main() -> int:
    errors = validate_registry()
    if errors:
        for error in errors:
            print(f"FAIL {error}")
        return 1
    print("PASS subject registry schema and cross-record checks")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
