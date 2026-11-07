import copy
import sys
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "scripts"))

from validate_subject_registry import EXPECTED_BUILDERS, load, validate_registry
from jsonschema import Draft202012Validator


class SubjectRegistryTests(unittest.TestCase):
    def setUp(self):
        self.schema = load(ROOT / "schemas/subject-registry-v1.schema.json")
        self.registry = load(ROOT / "subject-registry/registry.json")
        self.subject = self.registry["subjects"][0]

    def test_canonical_registry_passes_schema_and_cross_record_checks(self):
        self.assertEqual([], validate_registry(ROOT))

    def test_cryptography_is_the_single_initial_subject(self):
        self.assertEqual(1, len(self.registry["subjects"]))
        self.assertEqual("subject.cryptography", self.subject["subject_id"])
        self.assertEqual("Cryptography", self.subject["heading"])

    def test_all_nine_builders_are_assigned_exactly_once(self):
        ids = [item["builder_id"] for item in self.subject["stewardship"]]
        self.assertEqual(9, len(ids))
        self.assertEqual(EXPECTED_BUILDERS, set(ids))
        self.assertEqual(9, len(set(ids)))

    def test_every_subheading_has_valid_stewards_and_recognition_terms(self):
        for item in self.subject["subheadings"]:
            self.assertTrue(item["primary_stewards"])
            self.assertTrue(set(item["primary_stewards"]).issubset(EXPECTED_BUILDERS))
            self.assertTrue(item["recognition_terms"])

    def test_registry_rejects_unexpected_secret_material_fields(self):
        unsafe = copy.deepcopy(self.registry)
        unsafe["subjects"][0]["private_key"] = "must-not-be-stored"
        errors = list(Draft202012Validator(self.schema).iter_errors(unsafe))
        self.assertTrue(errors)

    def test_registry_requires_user_authority_and_nonexclusive_stewardship(self):
        self.assertTrue(self.subject["governance"]["user_is_final_authority"])
        self.assertTrue(self.subject["governance"]["primary_stewardship_is_nonexclusive"])
        self.assertTrue(self.subject["conversation_entry"]["user_is_first_class_participant"])
        self.assertTrue(self.subject["conversation_entry"]["any_builder_may_join"])


if __name__ == "__main__":
    unittest.main()
