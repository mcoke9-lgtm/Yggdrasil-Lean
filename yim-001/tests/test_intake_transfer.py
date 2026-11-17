import tempfile
import unittest
from pathlib import Path

from yim001.audited import AuditedMemoryStore, load_audited, save_audited
from yim001.intake import WorkIntakeQueue, create_intake_queue, load_intake_queue, save_intake_queue


class IntakeTransferTests(unittest.TestCase):
    def test_retry_after_store_save_does_not_duplicate_record(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            queue_path = root / "intake.json"
            store_path = root / "audited.json"
            queue = create_intake_queue(queue_path, "phone")
            packet = queue.add(
                {"text": "Original wording", "language": "English"},
                source_kind="chat_exchange", source_reference="checkpoint-10",
                language="English", metadata={"topic": "continuity"},
                captured_at="2026-11-17T16:00:00Z", packet_id="packet-10",
            )
            save_intake_queue(queue, queue_path)
            store = AuditedMemoryStore("phone")
            store.add_tree_location("roots.archive", "roots", "Living Archive")
            store.add_tree_location("branches.project", "branches", "Project")
            save_audited(store, store_path)

            # Simulate interruption after audited-store persistence but before queue update.
            first_store = load_audited(store_path)
            first = first_store.import_intake_packet(
                packet, queue_id="phone", primary_node_id="roots.archive",
                contextual_node_ids=["branches.project"],
            )
            save_audited(first_store, store_path)

            retry_store = load_audited(store_path)
            retry = retry_store.import_intake_packet(
                packet, queue_id="phone", primary_node_id="roots.archive",
                contextual_node_ids=["branches.project"],
            )
            self.assertEqual((retry.record_id, retry.version_id), (first.record_id, first.version_id))
            self.assertEqual(len(retry_store.versions(first.record_id)), 1)
            self.assertEqual(len(retry_store.events), 1)

            queue.mark_imported(
                "packet-10", primary_logical_location="roots.archive",
                contextual_logical_locations=["branches.project"],
                record_id=retry.record_id, version_id=retry.version_id,
            )
            save_intake_queue(queue, queue_path)
            self.assertEqual(len(load_intake_queue(queue_path).pending()), 0)
            final_store = load_audited(store_path)
            self.assertEqual(final_store.get_version(first.version_id).content, packet["content"])
            self.assertEqual(
                [(p.node_id, p.role) for p in final_store.placements_for(first.record_id)],
                [("roots.archive", "primary"), ("branches.project", "contextual")],
            )

    def test_retry_rejects_changed_packet_or_changed_placement(self):
        store = AuditedMemoryStore("phone")
        store.add_tree_location("roots.archive", "roots", "Living Archive")
        store.add_tree_location("branches.project", "branches", "Project")
        queue = WorkIntakeQueue("phone")
        packet = queue.add(
            "original", source_kind="note", language="English",
            packet_id="same-packet", captured_at="2026-11-17T16:00:00Z",
        )
        store.import_intake_packet(packet, queue_id="phone", primary_node_id="roots.archive")
        changed = dict(packet)
        changed["content"] = "changed after import"
        with self.assertRaisesRegex(ValueError, "content or metadata changed"):
            store.import_intake_packet(changed, queue_id="phone", primary_node_id="roots.archive")
        with self.assertRaisesRegex(ValueError, "different Tree placement"):
            store.import_intake_packet(packet, queue_id="phone", primary_node_id="branches.project")

    def test_queue_v1_snapshot_migrates_without_losing_pending_packet(self):
        queue = WorkIntakeQueue("phone")
        packet = queue.add("source", source_kind="note", language="English", packet_id="v1")
        snapshot = queue.to_snapshot()
        snapshot["format_version"] = 1
        for item in snapshot["packets"]:
            item.pop("imported_record_id")
            item.pop("imported_version_id")
        migrated = WorkIntakeQueue.from_snapshot(snapshot)
        self.assertEqual(migrated.pending()[0]["content"], packet["content"])
        self.assertIsNone(migrated.pending()[0]["imported_record_id"])

    def test_mark_imported_is_idempotent_and_rejects_conflicting_identity(self):
        queue = WorkIntakeQueue("phone")
        queue.add("source", source_kind="note", language="English", packet_id="p1")
        args = {
            "primary_logical_location": "roots.archive",
            "contextual_logical_locations": [],
            "record_id": "record-1",
            "version_id": "version-1",
        }
        first = queue.mark_imported("p1", **args)
        self.assertEqual(queue.mark_imported("p1", **args), first)
        with self.assertRaisesRegex(ValueError, "different identities"):
            queue.mark_imported("p1", **{**args, "record_id": "record-2"})


if __name__ == "__main__":
    unittest.main()
