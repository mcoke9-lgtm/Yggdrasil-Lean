"""Relationship regression cases transcribed from the 2026-10-27 pilot read.

This is a checked-in snapshot fixture, not a live Airtable adapter. It intentionally
preserves AF-PILOT-005's nonexistent source anchor so the resolver must fail closed.
"""
import unittest

from adaptive_filesystem_runtime import Record, ResolutionError, resolve_history


def pilot_snapshot():
    # Record ID, branch, sequence, parent, source branch, source anchor, revision target
    rows = [
        ("AF-PILOT-001", "chatgpt-first-integration", 1, None, None, None, None),
        ("AF-PILOT-002", "chatgpt-first-integration", 2, "AF-PILOT-001", None, None, "AF-PILOT-001"),
        ("AF-PILOT-003", "chatgpt-first-integration", 3, "AF-PILOT-002", None, None, "AF-PILOT-001"),
        ("AF-PILOT-004", "chatgpt-first-integration-fork-test", 1, "AF-PILOT-003",
         "chatgpt-first-integration", "AF-PILOT-001", None),
        ("AF-PILOT-005", "missing-anchor-test", 1, "AF-PILOT-004",
         "chatgpt-first-integration", "AF-ANCHOR-DOES-NOT-EXIST", None),
        ("AF-PILOT-006", "chatgpt-first-integration", 4, "AF-PILOT-005", None, None, None),
        ("AF-PILOT-007", "chatgpt-first-integration", 5, "AF-PILOT-006", None, None, None),
        ("AF-PILOT-008", "chatgpt-first-integration", 6, "AF-PILOT-007", None, None, None),
        ("AF-PILOT-009", "chatgpt-first-integration", 7, "AF-PILOT-008", None, None, None),
        ("AF-PILOT-010", "chatgpt-first-integration", 8, "AF-PILOT-009", None, None, None),
    ]
    return [Record(*row[:4], source_branch_id=row[4], source_anchor_id=row[5], revision_of_id=row[6])
            for row in rows]


class PilotSnapshotTests(unittest.TestCase):
    def test_live_observed_fork_anchor_resolves_exactly(self):
        result = resolve_history(pilot_snapshot(), "AF-PILOT-004")
        self.assertEqual(result.parent_record_id, "AF-PILOT-003")
        self.assertEqual(result.source_anchor_id, "AF-PILOT-001")
        self.assertEqual(result.branch_predecessor_id, None)

    def test_historical_missing_anchor_fixture_fails_closed(self):
        with self.assertRaises(ResolutionError) as caught:
            resolve_history(pilot_snapshot(), "AF-PILOT-005")
        self.assertEqual(caught.exception.code, "MISSING_SOURCE_ANCHOR")

    def test_revision_target_is_distinct_from_parent(self):
        result = resolve_history(pilot_snapshot(), "AF-PILOT-003")
        self.assertEqual(result.parent_record_id, "AF-PILOT-002")
        self.assertEqual(result.revision_of_id, "AF-PILOT-001")

    def test_global_parent_chain_and_branch_local_predecessor_are_distinct(self):
        result = resolve_history(pilot_snapshot(), "AF-PILOT-006")
        self.assertEqual(result.parent_record_id, "AF-PILOT-005")
        self.assertEqual(result.branch_predecessor_id, "AF-PILOT-003")


if __name__ == "__main__":
    unittest.main()
