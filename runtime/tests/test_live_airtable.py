"""Opt-in, read-only integration test against a live Airtable Records table.

Set AIRTABLE_PAT, AIRTABLE_BASE_ID, and AIRTABLE_TABLE_ID in the environment.
This test issues GET requests only. It never creates, updates, or deletes records.
"""
import json
import os
import unittest
from urllib.parse import quote, urlencode
from urllib.request import Request, urlopen

from adaptive_filesystem_runtime import ResolutionError, resolve_history
from adaptive_filesystem_runtime.airtable_adapter import records_from_airtable


REQUIRED_ENV = ("AIRTABLE_PAT", "AIRTABLE_BASE_ID", "AIRTABLE_TABLE_ID")
FIELDS = (
    "Record ID", "Branch ID", "Sequence", "Parent Record ID",
    "Source Branch ID", "Source Anchor ID", "Revision Of ID",
)


def fetch_all_records():
    token = os.environ["AIRTABLE_PAT"]
    base_id = os.environ["AIRTABLE_BASE_ID"]
    table_id = os.environ["AIRTABLE_TABLE_ID"]
    base_url = f"https://api.airtable.com/v0/{quote(base_id, safe='')}/{quote(table_id, safe='')}"
    rows = []
    offset = None
    while True:
        params = [("pageSize", "100")]
        params.extend(("fields[]", field) for field in FIELDS)
        if offset:
            params.append(("offset", offset))
        request = Request(
            base_url + "?" + urlencode(params),
            headers={"Authorization": f"Bearer {token}", "Accept": "application/json"},
            method="GET",
        )
        with urlopen(request, timeout=20) as response:
            payload = json.load(response)
        page = payload.get("records")
        if not isinstance(page, list):
            raise AssertionError("Airtable response did not contain a records list")
        rows.extend(page)
        offset = payload.get("offset")
        if not offset:
            return rows


@unittest.skipUnless(all(os.environ.get(name) for name in REQUIRED_ENV),
                     "live Airtable test requires AIRTABLE_PAT, AIRTABLE_BASE_ID, AIRTABLE_TABLE_ID")
class LiveAirtableIntegrationTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.rows = fetch_all_records()
        cls.records = records_from_airtable(cls.rows)
        cls.by_id = {record.record_id: record for record in cls.records}
        cls.ids = set(cls.by_id)

    def test_snapshot_contains_expected_pilot_records(self):
        expected = {f"AF-PILOT-{n:03d}" for n in range(1, 11)}
        self.assertTrue(expected.issubset(self.ids),
                        f"Missing expected application IDs: {sorted(expected - self.ids)}")

    def test_fork_anchor_and_parent_are_resolved_from_fresh_snapshot(self):
        result = resolve_history(self.records, "AF-PILOT-004")
        self.assertEqual(result.parent_record_id, "AF-PILOT-003")
        self.assertEqual(result.source_anchor_id, "AF-PILOT-001")
        self.assertEqual(result.parent_reference_state, "linked")
        self.assertEqual(result.source_anchor_reference_state, "linked")
        self.assertIsNone(result.branch_predecessor_id)
        self.assertEqual(result.branch_predecessor_state, "none_found_in_snapshot")

    def test_historical_missing_anchor_stays_explicit(self):
        with self.assertRaises(ResolutionError) as caught:
            resolve_history(self.records, "AF-PILOT-005")
        self.assertEqual(caught.exception.code, "MISSING_SOURCE_ANCHOR")

    def test_revision_parent_and_branch_predecessor_remain_distinct(self):
        result = resolve_history(self.records, "AF-PILOT-006")
        self.assertEqual(result.parent_record_id, "AF-PILOT-005")
        self.assertEqual(result.branch_predecessor_id, "AF-PILOT-003")
        self.assertEqual(result.parent_reference_state, "linked")
        self.assertEqual(result.branch_predecessor_state, "found_in_snapshot")
        revised = resolve_history(self.records, "AF-PILOT-003")
        self.assertEqual(revised.parent_record_id, "AF-PILOT-002")
        self.assertEqual(revised.revision_of_id, "AF-PILOT-001")
        self.assertEqual(revised.revision_reference_state, "linked")

    def test_absent_parent_on_007_is_preserved_not_inferred(self):
        self.assertIsNone(self.by_id["AF-PILOT-007"].parent_record_id)
        result = resolve_history(self.records, "AF-PILOT-007")
        self.assertIsNone(result.parent_record_id)
        self.assertEqual(result.parent_reference_state, "blank_unclassified")
        self.assertEqual(result.branch_predecessor_id, "AF-PILOT-006")
        self.assertEqual(result.branch_predecessor_state, "found_in_snapshot")


if __name__ == "__main__":
    unittest.main()
