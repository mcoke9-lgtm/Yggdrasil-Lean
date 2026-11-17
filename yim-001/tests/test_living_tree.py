import unittest

from yim001.living_tree import LivingTreeIndex, TreeIndexError


class LivingTreeIndexTests(unittest.TestCase):
    def setUp(self):
        self.index = LivingTreeIndex()
        self.index.add_node("root-memory", "roots", "Memory", "preserved source records")
        self.index.add_node("trunk-continuity", "trunk", "Continuity", "links across time", "root-memory")
        self.index.add_node("branch-language", "branches", "Language", "translation and meaning", "trunk-continuity")
        self.index.add_node("leaf-summary", "leaves", "Summaries", "presented retrieval forms", "branch-language")

    def test_primary_and_contextual_placements_are_distinct(self):
        self.index.place_record("conversation-1", "root-memory", "primary")
        self.index.place_record("conversation-1", "branch-language", "contextual")
        self.assertEqual(self.index.primary_location("conversation-1").node_id, "root-memory")
        self.assertEqual(
            [(p.node_id, p.role) for p in self.index.placements_for("conversation-1")],
            [("root-memory", "primary"), ("branch-language", "contextual")],
        )

    def test_record_retrieval_follows_recursive_tree_relationships(self):
        self.index.place_record("original", "root-memory")
        self.index.place_record("summary", "leaf-summary")
        self.assertEqual(
            self.index.records_at("root-memory"),
            ("original", "summary"),
        )
        self.assertEqual(self.index.records_at("root-memory", include_descendants=False), ("original",))

    def test_meaning_search_does_not_depend_on_physical_filename(self):
        self.index.place_record("original", "root-memory")
        matches = self.index.find_nodes("preserved SOURCE")
        self.assertEqual([node.node_id for node in matches], ["root-memory"])
        self.assertEqual(self.index.records_at(matches[0].node_id), ("original",))

    def test_snapshot_round_trip_preserves_logical_relationships(self):
        self.index.place_record("original", "root-memory")
        self.index.place_record("original", "branch-language", "contextual")
        restored = LivingTreeIndex.from_snapshot(self.index.to_snapshot())
        self.assertEqual(restored.to_snapshot(), self.index.to_snapshot())
        self.assertEqual(restored.primary_location("original").node_id, "root-memory")

    def test_only_one_primary_placement_is_allowed(self):
        self.index.place_record("original", "root-memory")
        with self.assertRaisesRegex(TreeIndexError, "already has a primary"):
            self.index.place_record("original", "trunk-continuity", "primary")

    def test_missing_parent_or_cycle_is_rejected_on_load(self):
        snapshot = self.index.to_snapshot()
        snapshot["nodes"][0]["parent_id"] = "missing"
        with self.assertRaisesRegex(TreeIndexError, "missing parents or a cycle"):
            LivingTreeIndex.from_snapshot(snapshot)

    def test_unknown_placement_target_is_rejected(self):
        with self.assertRaisesRegex(TreeIndexError, "Tree node not found"):
            self.index.place_record("original", "not-a-node")


if __name__ == "__main__":
    unittest.main()
