import unittest

from adaptive_filesystem_runtime import ResolutionError, resolve_history
from adaptive_filesystem_runtime.airtable_adapter import records_from_airtable


def row(record_id, branch, sequence, parent=None, source_branch=None, anchor=None, revision=None):
    return {"id": "airtable-internal-id-is-not-app-id", "fields": {
        "Record ID": record_id,
        "Branch ID": branch,
        "Sequence": sequence,
        "Parent Record ID": parent,
        "Source Branch ID": source_branch,
        "Source Anchor ID": anchor,
        "Revision Of ID": revision,
    }}


class AirtableAdapterTests(unittest.TestCase):
    def test_maps_pilot_field_names_and_keeps_relationships_separate(self):
        rows = [
            row("AF-PILOT-001", "chatgpt-first-integration", 1),
            row("AF-PILOT-002", "chatgpt-first-integration", 2,
                parent="AF-PILOT-001", revision="AF-PILOT-001"),
            row("AF-PILOT-003", "chatgpt-first-integration", 3,
                parent="AF-PILOT-002", revision="AF-PILOT-001"),
            row("AF-PILOT-004", "chatgpt-first-integration-fork-test", 1,
                parent="AF-PILOT-003", source_branch="chatgpt-first-integration",
                anchor="AF-PILOT-001"),
        ]
        records = records_from_airtable(rows)
        result = resolve_history(records, "AF-PILOT-004")
        self.assertEqual(result.parent_record_id, "AF-PILOT-003")
        self.assertEqual(result.source_anchor_id, "AF-PILOT-001")
        self.assertIsNone(result.revision_of_id)

    def test_missing_anchor_is_not_repaired(self):
        rows = [
            row("AF-PILOT-004", "chatgpt-first-integration-fork-test", 1),
            row("AF-PILOT-005", "missing-anchor-test", 1, parent="AF-PILOT-004",
                source_branch="chatgpt-first-integration", anchor="AF-ANCHOR-DOES-NOT-EXIST")
        ]
        with self.assertRaises(ResolutionError) as caught:
            resolve_history(records_from_airtable(rows), "AF-PILOT-005")
        self.assertEqual(caught.exception.code, "MISSING_SOURCE_ANCHOR")

    def test_whitespace_reference_fails_closed(self):
        with self.assertRaises(ResolutionError) as caught:
            records_from_airtable([row("x", "main", 1, parent=" AF-PILOT-001")])
        self.assertEqual(caught.exception.code, "MALFORMED_REFERENCE")

    def test_noninteger_sequence_is_rejected(self):
        for value in (1.5, True, "2"):
            with self.subTest(value=value):
                with self.assertRaises(ResolutionError) as caught:
                    records_from_airtable([row("x", "main", value)])
                self.assertEqual(caught.exception.code, "MALFORMED_FIELD")

    def test_negative_sequence_is_rejected_by_resolver(self):
        with self.assertRaises(ResolutionError) as caught:
            resolve_history(records_from_airtable([row("x", "main", -1)]), "x")
        self.assertEqual(caught.exception.code, "MALFORMED_RECORD")

    def test_internal_airtable_id_is_not_used_as_application_id(self):
        records = records_from_airtable([row("AF-PILOT-001", "main", 1)])
        self.assertEqual(records[0].record_id, "AF-PILOT-001")

    def test_nonobject_row_and_missing_fields_are_rejected(self):
        for bad_row in (None, {"id": "x"}, {"fields": []}):
            with self.subTest(bad_row=bad_row):
                with self.assertRaises(ResolutionError) as caught:
                    records_from_airtable([bad_row])
                self.assertEqual(caught.exception.code, "MALFORMED_AIRTABLE_ROW")

    def test_required_text_fields_reject_blank_or_whitespace(self):
        cases = [
            row("", "main", 1),
            row("x", "", 1),
            row(" x ", "main", 1),
            row("x", " main", 1),
        ]
        for bad_row in cases:
            with self.subTest(fields=bad_row["fields"]):
                with self.assertRaises(ResolutionError) as caught:
                    records_from_airtable([bad_row])
                self.assertEqual(caught.exception.code, "MALFORMED_FIELD")

    def test_source_branch_and_anchor_must_be_paired(self):
        for bad_row in (
            row("x", "fork", 1, source_branch="main"),
            row("x", "fork", 1, anchor="anchor"),
        ):
            with self.subTest(fields=bad_row["fields"]):
                with self.assertRaises(ResolutionError) as caught:
                    resolve_history(records_from_airtable([bad_row]), "x")
                self.assertEqual(caught.exception.code, "MALFORMED_SOURCE_REFERENCE")

    def test_nontext_optional_reference_is_rejected(self):
        with self.assertRaises(ResolutionError) as caught:
            records_from_airtable([row("x", "main", 1, parent=123)])
        self.assertEqual(caught.exception.code, "MALFORMED_FIELD")

    def test_duplicate_application_ids_are_rejected(self):
        rows = [row("x", "main", 1), row("x", "fork", 1)]
        with self.assertRaises(ResolutionError) as caught:
            resolve_history(records_from_airtable(rows), "x")
        self.assertEqual(caught.exception.code, "DUPLICATE_RECORD_ID")

    def test_duplicate_branch_sequence_is_rejected(self):
        rows = [row("x", "main", 1), row("y", "main", 1)]
        with self.assertRaises(ResolutionError) as caught:
            resolve_history(records_from_airtable(rows), "x")
        self.assertEqual(caught.exception.code, "DUPLICATE_BRANCH_SEQUENCE")


if __name__ == "__main__":
    unittest.main()
