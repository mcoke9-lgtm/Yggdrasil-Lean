import unittest

from adaptive_filesystem_runtime import Record, audit_snapshot


class SnapshotAuditTests(unittest.TestCase):
    def test_clean_snapshot_passes_but_reports_unclassified_blanks(self):
        records = [
            Record("a", "main", 1),
            Record("b", "main", 2, parent_record_id="a"),
        ]
        report = audit_snapshot(records)
        self.assertEqual(report.status, "passed")
        self.assertEqual(report.record_count, 2)
        self.assertEqual(report.error_count, 0)
        self.assertGreater(report.unclassified_count, 0)
        self.assertTrue(all(f.severity == "info" for f in report.findings))

    def test_missing_source_anchor_is_reported_snapshot_wide(self):
        records = [
            Record("a", "main", 1),
            Record("fork", "fork", 1, source_branch_id="main", source_anchor_id="absent"),
        ]
        report = audit_snapshot(records)
        self.assertEqual(report.status, "failed")
        self.assertTrue(any(f.code == "MISSING_SOURCE_ANCHOR" and f.record_id == "fork"
                            for f in report.findings))

    def test_blank_parent_is_not_repaired_or_called_root(self):
        report = audit_snapshot([Record("a", "main", 1)])
        blank = [f for f in report.findings if f.record_id == "a" and f.relationship == "parent"]
        self.assertEqual(len(blank), 1)
        self.assertEqual(blank[0].code, "BLANK_UNCLASSIFIED")
        self.assertIn("cannot be distinguished", blank[0].detail)

    def test_all_parent_cycles_are_detected(self):
        records = [
            Record("a", "main", 1, parent_record_id="b"),
            Record("b", "main", 2, parent_record_id="a"),
            Record("c", "other", 1, parent_record_id="d"),
            Record("d", "other", 2, parent_record_id="c"),
        ]
        report = audit_snapshot(records)
        cycles = [f for f in report.findings if f.code == "PARENT_CYCLE"]
        self.assertEqual(len(cycles), 2)
        self.assertEqual(report.status, "failed")

    def test_malformed_snapshot_returns_structural_failure(self):
        report = audit_snapshot([
            Record("a", "main", 1),
            Record("b", "main", 1),
        ])
        self.assertEqual(report.status, "failed")
        self.assertEqual(report.error_count, 1)
        self.assertEqual(report.findings[0].code, "DUPLICATE_BRANCH_SEQUENCE")


if __name__ == "__main__":
    unittest.main()
