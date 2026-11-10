import math
import unittest

from yim001 import MemoryStore, NotFoundError, RecordError, ValidationError


class MemoryStoreTests(unittest.TestCase):
    def setUp(self):
        self.store = MemoryStore()

    def test_a1_stable_record_identifier(self):
        v = self.store.create_record({"x": 1})
        self.assertTrue(v.record_id)
        self.assertEqual(self.store.versions(v.record_id)[0].record_id, v.record_id)

    def test_a2_create_initial_version(self):
        v = self.store.create_record({"hello": "world"})
        self.assertEqual(v.parents, ())
        self.assertEqual(v.content, {"hello": "world"})

    def test_a3_revision_preserves_prior_version(self):
        first = self.store.create_record({"n": 1})
        second = self.store.revise(first.record_id, {"n": 2})
        self.assertEqual(self.store.get_version(first.version_id).content, {"n": 1})
        self.assertEqual(second.parents, (first.version_id,))

    def test_a4_retrieve_individual_version(self):
        first = self.store.create_record([1])
        second = self.store.revise(first.record_id, [2])
        self.assertEqual(self.store.get_version(second.version_id).content, [2])

    def test_a5_preserve_version_relationships(self):
        first = self.store.create_record(0)
        second = self.store.revise(first.record_id, 1)
        third = self.store.revise(first.record_id, 2, parent_version_ids=[first.version_id])
        self.assertEqual(second.parents, (first.version_id,))
        self.assertEqual(third.parents, (first.version_id,))
        self.assertEqual(set(self.store.tips(first.record_id)), {second.version_id, third.version_id})

    def test_a6_explicit_conflict_annotation(self):
        first = self.store.create_record("a")
        second = self.store.revise(first.record_id, "b")
        conflict = self.store.annotate_conflict(first.version_id, second.version_id)
        self.assertIn(conflict, self.store.conflicts(first.record_id))

    def test_a7_earlier_versions_readable_after_revision(self):
        first = self.store.create_record({"v": 1})
        for n in range(2, 6):
            self.store.revise(first.record_id, {"v": n})
        self.assertEqual(self.store.get_version(first.version_id).content, {"v": 1})

    def test_a8_competing_revisions_remain_visible(self):
        first = self.store.create_record("base")
        left = self.store.revise(first.record_id, "left")
        right = self.store.revise(first.record_id, "right", parent_version_ids=[first.version_id])
        self.assertEqual(set(self.store.tips(first.record_id)), {left.version_id, right.version_id})

    def test_a9_rejects_non_json_values_recursively_before_write(self):
        first = self.store.create_record({"ok": True})
        before = len(self.store.versions(first.record_id))
        for bad in [float("nan"), float("inf"), -float("inf"), object()]:
            with self.assertRaises(ValidationError):
                self.store.revise(first.record_id, {"nested": [bad]})
            self.assertEqual(len(self.store.versions(first.record_id)), before)
        with self.assertRaises(ValidationError):
            self.store.create_record({"bad": math.nan})

    def test_a10_conflicts_are_directional(self):
        first = self.store.create_record("a")
        second = self.store.revise(first.record_id, "b")
        self.store.annotate_conflict(first.version_id, second.version_id)
        conflicts = self.store.conflicts(first.record_id)
        self.assertEqual(len(conflicts), 1)
        self.assertEqual((conflicts[0].source_version_id, conflicts[0].target_version_id), (first.version_id, second.version_id))

    def test_metadata_is_validated_and_detached(self):
        metadata = {"tags": ["a"]}
        version = self.store.create_record({}, metadata)
        metadata["tags"].append("b")
        self.assertEqual(version.metadata, {"tags": ["a"]})
        with self.assertRaises(ValidationError):
            self.store.create_record({}, {1: "not a JSON object key"})

    def test_cyclic_data_rejected(self):
        value = []
        value.append(value)
        with self.assertRaises(ValidationError):
            self.store.create_record(value)

    def test_cross_record_parent_and_conflict_rejected(self):
        a = self.store.create_record("a")
        b = self.store.create_record("b")
        with self.assertRaises(RecordError):
            self.store.revise(a.record_id, "bad", parent_version_ids=[b.version_id])
        with self.assertRaises(RecordError):
            self.store.annotate_conflict(a.version_id, b.version_id)

    def test_unknown_ids_raise_not_found(self):
        with self.assertRaises(NotFoundError):
            self.store.get_version("missing")
        with self.assertRaises(NotFoundError):
            self.store.versions("missing")


if __name__ == "__main__":
    unittest.main()
