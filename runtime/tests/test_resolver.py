import unittest
from adaptive_filesystem_runtime import Record, ResolutionError, resolve_history


class ResolverTests(unittest.TestCase):
    def setUp(self):
        self.base = Record("base-1", "main", 1)
        self.main2 = Record("main-2", "main", 2, parent_record_id="base-1")
        self.fork1 = Record("fork-1", "fork", 1, parent_record_id="main-2",
                            source_branch_id="main", source_anchor_id="base-1")
        self.revision = Record("revision-1", "main", 3, parent_record_id="fork-1",
                               revision_of_id="base-1")

    def test_parent_source_anchor_and_revision_are_independent(self):
        result = resolve_history([self.base, self.main2, self.fork1, self.revision], "revision-1")
        self.assertEqual(result.parent_record_id, "fork-1")
        self.assertEqual(result.revision_of_id, "base-1")
        self.assertIsNone(result.source_anchor_id)
        self.assertEqual(result.branch_predecessor_id, "main-2")
        self.assertEqual(result.parent_reference_state, "linked")
        self.assertEqual(result.source_anchor_reference_state, "blank_unclassified")
        self.assertEqual(result.revision_reference_state, "linked")
        self.assertEqual(result.branch_predecessor_state, "found_in_snapshot")

    def test_blank_parent_is_not_promoted_to_explicit_root_or_na(self):
        result = resolve_history([self.base], "base-1")
        self.assertIsNone(result.parent_record_id)
        self.assertEqual(result.parent_reference_state, "blank_unclassified")
        self.assertEqual(result.branch_predecessor_state, "none_found_in_snapshot")

    def test_fork_anchor_and_parent_resolve(self):
        result = resolve_history([self.base, self.main2, self.fork1], "fork-1")
        self.assertEqual(result.source_anchor_id, "base-1")
        self.assertEqual(result.parent_record_id, "main-2")
        self.assertEqual(result.source_anchor_reference_state, "linked")

    def test_missing_requested_record(self):
        with self.assertRaises(ResolutionError) as caught:
            resolve_history([self.base], "absent")
        self.assertEqual(caught.exception.code, "MISSING_RECORD")

    def test_missing_parent(self):
        with self.assertRaises(ResolutionError) as caught:
            resolve_history([Record("x", "main", 2, parent_record_id="absent")], "x")
        self.assertEqual(caught.exception.code, "MISSING_PARENT")

    def test_unrelated_broken_parent_does_not_block_requested_record(self):
        good = Record("good", "main", 1)
        unrelated = Record("unrelated", "other", 1, parent_record_id="absent")
        result = resolve_history([good, unrelated], "good")
        self.assertEqual(result.record_id, "good")
        self.assertEqual(result.parent_reference_state, "blank_unclassified")

    def test_missing_source_anchor(self):
        record = Record("fork", "fork", 1, source_branch_id="main", source_anchor_id="absent")
        with self.assertRaises(ResolutionError) as caught:
            resolve_history([record], "fork")
        self.assertEqual(caught.exception.code, "MISSING_SOURCE_ANCHOR")

    def test_source_branch_mismatch(self):
        other = Record("anchor", "other", 1)
        fork = Record("fork", "fork", 1, source_branch_id="main", source_anchor_id="anchor")
        with self.assertRaises(ResolutionError) as caught:
            resolve_history([other, fork], "fork")
        self.assertEqual(caught.exception.code, "SOURCE_BRANCH_MISMATCH")

    def test_missing_revision_target(self):
        with self.assertRaises(ResolutionError) as caught:
            resolve_history([Record("revision", "main", 2, revision_of_id="absent")], "revision")
        self.assertEqual(caught.exception.code, "MISSING_REVISION_TARGET")

    def test_parent_cycle(self):
        a = Record("a", "main", 1, parent_record_id="b")
        b = Record("b", "main", 2, parent_record_id="a")
        with self.assertRaises(ResolutionError) as caught:
            resolve_history([a, b], "a")
        self.assertEqual(caught.exception.code, "PARENT_CYCLE")

    def test_duplicate_record_id(self):
        with self.assertRaises(ResolutionError) as caught:
            resolve_history([self.base, self.base], "base-1")
        self.assertEqual(caught.exception.code, "DUPLICATE_RECORD_ID")

    def test_duplicate_branch_sequence(self):
        a, b = Record("a", "main", 1), Record("b", "main", 1)
        with self.assertRaises(ResolutionError) as caught:
            resolve_history([a, b], "a")
        self.assertEqual(caught.exception.code, "DUPLICATE_BRANCH_SEQUENCE")


if __name__ == "__main__":
    unittest.main()
