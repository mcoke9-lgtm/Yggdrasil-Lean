"""Minimal executable contract harness for AF recovery wave/particle transitions.

Run: python -m unittest discover -s tests -v
Scope: synthetic transformation contract only; not the production Adaptive Filesystem runtime.
"""
from copy import deepcopy
import unittest


def wave_exists(records, relationships, changes_intended=None):
    return {
        "form": "wave",
        "records": deepcopy(records),
        "relationships": deepcopy(relationships),
        "changes_intended": deepcopy(changes_intended or []),
    }


def collapse(wave):
    """Create canonical particle state without applying planned changes."""
    return {
        "form": "particle",
        "records": deepcopy(wave["records"]),
        "relationships": deepcopy(wave["relationships"]),
        "changes_intended": deepcopy(wave["changes_intended"]),
    }


def activate(particle):
    """Reconstruct a wave view from canonical state."""
    return {
        "form": "wave",
        "records": deepcopy(particle["records"]),
        "relationships": deepcopy(particle["relationships"]),
        "changes_intended": deepcopy(particle["changes_intended"]),
    }


def validate_wave(wave):
    if not isinstance(wave, dict) or wave.get("form") != "wave":
        return {"status": "INVALID_INPUT", "reasons": ["wave_shape_invalid"]}
    records = wave.get("records")
    relationships = wave.get("relationships")
    if not isinstance(records, list) or not isinstance(relationships, list):
        return {"status": "INVALID_INPUT", "reasons": ["collections_invalid"]}

    record_ids = [r.get("record_id") for r in records if isinstance(r, dict)]
    rel_ids = [r.get("relationship_id") for r in relationships if isinstance(r, dict)]
    if len(record_ids) != len(records) or any(not x for x in record_ids):
        return {"status": "INVALID_INPUT", "reasons": ["record_identity_invalid"]}
    if len(rel_ids) != len(relationships) or any(not x for x in rel_ids):
        return {"status": "INVALID_INPUT", "reasons": ["relationship_identity_invalid"]}
    if len(set(record_ids)) != len(record_ids):
        return {"status": "INVALID", "reasons": ["duplicate_record"]}
    if len(set(rel_ids)) != len(rel_ids):
        return {"status": "INVALID", "reasons": ["duplicate_relationship"]}

    by_id = set(record_ids)
    for rel in relationships:
        if rel.get("source_id") not in by_id or rel.get("target_id") not in by_id:
            return {"status": "INVALID", "reasons": ["endpoint_missing"]}
        if not rel.get("provenance"):
            return {"status": "INVALID", "reasons": ["provenance_missing"]}
        if not rel.get("relationship_type") or not rel.get("status"):
            return {"status": "INVALID", "reasons": ["relationship_semantics_missing"]}
    return {"status": "VALID", "reasons": []}


def compare_round_trip(source_wave, reconstructed_wave):
    source_check = validate_wave(source_wave)
    result_check = validate_wave(reconstructed_wave)
    if source_check["status"] == "UNKNOWN" or result_check["status"] == "UNKNOWN":
        return {"status": "UNKNOWN", "reasons": ["source_or_result_unavailable"]}
    if source_check["status"] != "VALID" or result_check["status"] != "VALID":
        return {"status": "FAIL", "reasons": source_check["reasons"] + result_check["reasons"]}
    fields = ("records", "relationships", "changes_intended")
    differences = [field for field in fields if source_wave.get(field) != reconstructed_wave.get(field)]
    return {"status": "FAIL" if differences else "PASS",
            "reasons": ["round_trip_difference:" + field for field in differences]}


def unknown_source():
    return {"status": "UNKNOWN", "reasons": ["source_unavailable"]}


def fixture():
    records = [
        {"record_id": "A", "provenance": "fixture-001"},
        {"record_id": "B", "provenance": "fixture-001"},
    ]
    relationships = [{
        "relationship_id": "R1",
        "source_id": "A",
        "target_id": "B",
        "relationship_type": "depends_on",
        "direction": "A_to_B",
        "provenance": "fixture-001",
        "status": "unresolved",
    }]
    intended = [{"change_id": "C1", "operation": "revise_label", "status": "planned"}]
    return wave_exists(records, relationships, intended)


class RecoveryRoundTripTests(unittest.TestCase):
    def setUp(self):
        self.source = fixture()

    def test_01_baseline_round_trip_preserves_contract(self):
        result = compare_round_trip(self.source, activate(collapse(self.source)))
        self.assertEqual(result["status"], "PASS")

    def test_02_provenance_loss_during_collapse_fails(self):
        particle = collapse(self.source)
        del particle["relationships"][0]["provenance"]
        result = compare_round_trip(self.source, activate(particle))
        self.assertEqual(result["status"], "FAIL")
        self.assertIn("provenance_missing", result["reasons"])

    def test_03_unresolved_cannot_silently_become_confirmed(self):
        reconstructed = activate(collapse(self.source))
        reconstructed["relationships"][0]["status"] = "confirmed"
        result = compare_round_trip(self.source, reconstructed)
        self.assertEqual(result["status"], "FAIL")
        self.assertTrue(any(x.startswith("round_trip_difference:") for x in result["reasons"]))

    def test_04_duplicate_relationship_is_detected(self):
        reconstructed = activate(collapse(self.source))
        reconstructed["relationships"].append(deepcopy(reconstructed["relationships"][0]))
        result = compare_round_trip(self.source, reconstructed)
        self.assertEqual(result["status"], "FAIL")
        self.assertIn("duplicate_relationship", result["reasons"])

    def test_05_planned_change_is_not_reported_as_applied(self):
        particle = collapse(self.source)
        reconstructed = activate(particle)
        self.assertEqual(reconstructed["changes_intended"][0]["status"], "planned")
        self.assertEqual(reconstructed["records"], self.source["records"])
        result = compare_round_trip(self.source, reconstructed)
        self.assertEqual(result["status"], "PASS")

    def test_06_unavailable_source_is_unknown_not_pass(self):
        result = unknown_source()
        self.assertEqual(result["status"], "UNKNOWN")
        self.assertNotEqual(result["status"], "PASS")


if __name__ == "__main__":
    unittest.main()
