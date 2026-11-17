import tempfile
import unittest
from pathlib import Path

from yim001.audited import AuditedMemoryStore, load_audited, save_audited


class LivingTreePersistenceTests(unittest.TestCase):
    def test_record_placement_survives_save_and_restart(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "memory.json"
            store = AuditedMemoryStore("test-operator")
            store.add_tree_location("memory-root", "roots", "Memory", "preserved original records")
            store.add_tree_location("language-branch", "branches", "Language", "translation and meaning", "memory-root")
            original = store.create_record(
                {"text": "The original conversation", "language": "en"},
                timestamp="2026-11-17T12:00:00Z",
            )
            store.place_record(original.record_id, "memory-root", timestamp="2026-11-17T12:01:00Z")
            store.place_record(original.record_id, "language-branch", "contextual", timestamp="2026-11-17T12:02:00Z")
            save_audited(store, path)

            # Simulate process/session restart by discarding the original object.
            del store
            restored = load_audited(path)

            self.assertEqual(restored.primary_location(original.record_id).node_id, "memory-root")
            self.assertEqual(
                [(p.node_id, p.role) for p in restored.placements_for(original.record_id)],
                [("memory-root", "primary"), ("language-branch", "contextual")],
            )
            self.assertEqual(restored.records_at_tree_location("memory-root"), (original.record_id,))
            self.assertEqual(len(restored.events), 3)
            self.assertEqual(restored.events[-1].event_type, "record.tree_placed")

    def test_meaning_lookup_then_record_retrieval_after_restart(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "memory.json"
            store = AuditedMemoryStore("test-operator")
            store.add_tree_location("roots-memory", "roots", "Memory", "durable preserved source")
            version = store.create_record({"source": "conversation"}, timestamp="2026-11-17T12:00:00Z")
            store.place_record(version.record_id, "roots-memory", timestamp="2026-11-17T12:01:00Z")
            save_audited(store, path)

            restored = load_audited(path)
            matches = restored.find_tree_locations("preserved source")
            self.assertEqual([node.node_id for node in matches], ["roots-memory"])
            self.assertEqual(restored.records_at_tree_location(matches[0].node_id), (version.record_id,))

    def test_legacy_version_one_snapshot_loads_without_tree_index(self):
        legacy = {
            "format": AuditedMemoryStore.FORMAT,
            "format_version": 1,
            "actor_id": "legacy",
            "memory": __import__("yim001.core", fromlist=["MemoryStore"]).MemoryStore().to_snapshot(),
            "provenance": __import__("yim001.provenance", fromlist=["EventLedger"]).EventLedger().to_snapshot(),
        }
        restored = AuditedMemoryStore.from_snapshot(legacy)
        self.assertEqual(restored.tree_nodes(), ())
        self.assertEqual(restored.to_snapshot()["format_version"], 2)


if __name__ == "__main__":
    unittest.main()
