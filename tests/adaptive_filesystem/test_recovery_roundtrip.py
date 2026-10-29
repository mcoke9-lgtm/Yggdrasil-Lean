"""Minimal executable contract harness for AF recovery wave/particle transitions.

Run: python -m unittest discover -s tests -v
Scope: synthetic transformation contract only; not the production Adaptive Filesystem runtime.

Model mapping:
- Initial wave: TERA=system under test, FIRA=fixture, WATA=runner, AIRA=assertions.
- Particle: report generation is AIRA-of-TERA (leaf-like part(s) of the roots),
  not the initial-wave AIRA assertion role.
- The report generates the second FIRA-associated wave that the reader reads.
"""
from copy import deepcopy
import unittest


def wave_exists(records, relationships, changes_intended=None):
    return {
        "form": "wave",
        "association": "initial",
        "records": deepcopy(records),
        "relationships": deepcopy(relationships),
        "changes_intended": deepcopy(changes_intended or []),
    }


def collapse(wave):
    """Compose particle state without applying planned changes."""
    return {
        "form": "particle",
        "tera_state": {
            "records": deepcopy(wave["records"]),
            "relationships": deepcopy(wave["relationships"]),
            "changes_intended": deepcopy(wave["changes_intended"]),
        },
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


def assertions_aira(source_wave, reconstructed_wave):
    """Initial-wave AIRA role: assertions evaluate evidence; they do not report."""
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


def aira_of_tera_report(particle):
    """Particle-form report generation from TERA's root state and leaf-like parts."""
    tera_state = particle.get("tera_state", {})
    records = tera_state.get("records", [])
    relationships = tera_state.get("relationships", [])
    leaf_parts = [
        {"record_id": record.get("record_id"),
         "provenance": record.get("provenance"),
         "role": record.get("role")}
        for record in records
        if isinstance(record, dict) and record.get("role") == "leaf"
    ]
    relationship_evidence = [
        {"relationship_id": rel.get("relationship_id"),
         "status": rel.get("status"),
         "provenance": rel.get("provenance")}
        for rel in relationships if isinstance(rel, dict)
    ]
    return {
        "generated_by": "AIRA-of-TERA",
        "form": "particle_report",
        "tera_state": deepcopy(tera_state),
        "leaf_parts": leaf_parts,
        "relationship_evidence": relationship_evidence,
        "changes_intended": deepcopy(tera_state.get("changes_intended", [])),
    }


def activate(report):
    """Generate the second FIRA-associated wave from the particle report."""
    return {"form": "wave", "association": "second_fira", "report": deepcopy(report)}


def unknown_source():
    return {"status": "UNKNOWN", "reasons": ["source_unavailable"]}


def fixture():
    records = [
        {"record_id": "A", "provenance": "fixture-001", "role": "root"},
        {"record_id": "B", "provenance": "fixture-001", "role": "leaf"},
    ]
    relationships = [{
        "relationship_id": "R1", "source_id": "A", "target_id": "B",
        "relationship_type": "contains", "direction": "A_to_B",
        "provenance": "fixture-001", "status": "unresolved",
    }]
    intended = [{"change_id": "C1", "operation": "revise_label", "status": "planned"}]
    return wave_exists(records, relationships, intended)


class RecoveryRoundTripTests(unittest.TestCase):
    def setUp(self):
        self.source = fixture()
        self.particle = collapse(self.source)
        self.report = aira_of_tera_report(self.particle)
        self.second_wave = activate(self.report)

    def test_01_initial_wave_assertions_are_separate_from_report_generation(self):
        reconstructed = deepcopy(self.source)
        result = assertions_aira(self.source, reconstructed)
        self.assertEqual(result["status"], "PASS")
        self.assertEqual(self.report["generated_by"], "AIRA-of-TERA")

    def test_02_report_is_generated_from_particle_tera_state(self):
        self.assertEqual(self.report["form"], "particle_report")
        self.assertEqual(self.report["tera_state"], self.particle["tera_state"])
        self.assertEqual(self.report["leaf_parts"][0]["record_id"], "B")

    def test_03_second_wave_is_fira_associated_and_derived_from_report(self):
        self.assertEqual(self.second_wave["form"], "wave")
        self.assertEqual(self.second_wave["association"], "second_fira")
        self.assertEqual(self.second_wave["report"], self.report)

    def test_04_provenance_loss_in_particle_report_remains_visible(self):
        damaged = collapse(self.source)
        del damaged["tera_state"]["relationships"][0]["provenance"]
        report = aira_of_tera_report(damaged)
        self.assertIsNone(report["relationship_evidence"][0]["provenance"])

    def test_05_unresolved_cannot_silently_become_confirmed(self):
        changed = deepcopy(self.source)
        changed["relationships"][0]["status"] = "confirmed"
        result = assertions_aira(self.source, changed)
        self.assertEqual(result["status"], "FAIL")
        self.assertTrue(any(x.startswith("round_trip_difference:") for x in result["reasons"]))

    def test_06_planned_change_remains_planned_not_applied(self):
        self.assertEqual(self.report["changes_intended"][0]["status"], "planned")
        self.assertEqual(self.report["tera_state"]["records"], self.source["records"])

    def test_07_duplicate_relationship_is_detected_by_initial_wave_assertions(self):
        reconstructed = deepcopy(self.source)
        reconstructed["relationships"].append(deepcopy(reconstructed["relationships"][0]))
        result = assertions_aira(self.source, reconstructed)
        self.assertEqual(result["status"], "FAIL")
        self.assertIn("duplicate_relationship", result["reasons"])

    def test_08_unavailable_source_is_unknown_not_pass(self):
        result = unknown_source()
        self.assertEqual(result["status"], "UNKNOWN")
        self.assertNotEqual(result["status"], "PASS")



def post_connection_report(source_wave, particle, second_wave, assertion_result):
    """Sixth workflow component: report the result after the connection is formed."""
    report = second_wave.get("report", {}) if isinstance(second_wave, dict) else {}
    connected = (
        isinstance(second_wave, dict)
        and second_wave.get("form") == "wave"
        and second_wave.get("association") == "second_fira"
        and report.get("generated_by") == "AIRA-of-TERA"
        and report.get("tera_state") == particle.get("tera_state")
    )
    return {
        "component": 6,
        "name": "post_connection_report",
        "status": "GENERATED" if connected else "INCOMPLETE",
        "source_assessment": deepcopy(assertion_result),
        "source_wave_association": source_wave.get("association"),
        "particle_state_present": isinstance(particle.get("tera_state"), dict),
        "second_wave_association": second_wave.get("association") if isinstance(second_wave, dict) else None,
        "report_generator": report.get("generated_by"),
        "changes_intended": deepcopy(report.get("changes_intended", [])),
        "unresolved": [] if connected else ["post_connection_contract_not_satisfied"],
        "scope_note": "Synthetic contract report; not a production runtime report.",
    }


def diagnose_missing_sixth_component(schema_fields, required_outputs):
    """Explain whether schema fields alone require a post-connection report."""
    missing = []
    if "post_connection_report" not in required_outputs:
        missing.append("post_connection_report_not_declared_as_required_output")
    if "post_connection_report" not in schema_fields:
        missing.append("schema_has_no_explicit_report_component")
    if not any("post_connection" in str(item) for item in required_outputs):
        missing.append("no_post_connection_generation_invariant")
    return {
        "status": "GAP_IDENTIFIED" if missing else "REQUIREMENT_PRESENT",
        "findings": list(dict.fromkeys(missing)),
        "cause_scope": "Design-level diagnosis from inspected schema contract; historical runtime cause remains unknown.",
    }




class PostConnectionReportTests(unittest.TestCase):
    def setUp(self):
        self.source = fixture()
        self.particle = collapse(self.source)
        self.report = aira_of_tera_report(self.particle)
        self.second_wave = activate(self.report)

    def test_09_post_connection_report_is_generated_as_sixth_component(self):
        assertion = assertions_aira(self.source, deepcopy(self.source))
        report = post_connection_report(self.source, self.particle, self.second_wave, assertion)
        self.assertEqual(report["component"], 6)
        self.assertEqual(report["status"], "GENERATED")
        self.assertEqual(report["report_generator"], "AIRA-of-TERA")
        self.assertEqual(report["second_wave_association"], "second_fira")

    def test_10_missing_sixth_component_is_diagnosed_as_schema_requirement_gap(self):
        diagnostic = diagnose_missing_sixth_component(
            schema_fields=["purpose", "branch", "state", "blockers", "next_action", "changes_intended"],
            required_outputs=["particle_state", "second_wave"],
        )
        self.assertEqual(diagnostic["status"], "GAP_IDENTIFIED")
        self.assertIn("post_connection_report_not_declared_as_required_output", diagnostic["findings"])
        self.assertIn("no_post_connection_generation_invariant", diagnostic["findings"])
        self.assertIn("historical runtime cause remains unknown", diagnostic["cause_scope"])


if __name__ == "__main__":
    unittest.main()
