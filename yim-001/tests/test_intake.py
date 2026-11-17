import json
import tempfile
import unittest
from pathlib import Path

from yim001.intake import (
    IntakeQueueError, WorkIntakeQueue, create_intake_queue,
    load_intake_queue, save_intake_queue,
)
from yim001.audited import AuditedMemoryStore, load_audited, save_audited


class WorkIntakeQueueTests(unittest.TestCase):
    def test_packet_preserves_source_language_and_explicit_unplaced_state(self):
        queue = WorkIntakeQueue("phone-intake")
        original = {"text": "Keep this wording.", "nested": [1, 2]}
        packet = queue.add(
            original, source_kind="chat_exchange",
            source_reference="session-checkpoint-7", language="English",
            metadata={"topic": "continuity"},
            captured_at="2026-11-17T16:00:00Z", packet_id="packet-7",
        )
        self.assertEqual(packet["content"], original)
        self.assertEqual(packet["language"], "English")
        self.assertEqual(packet["placement_status"], "awaiting_placement")
        self.assertIsNone(packet["primary_logical_location"])
        self.assertEqual(packet["contextual_logical_locations"], [])
        self.assertTrue(packet["source_content_preserved_as_submitted"])
        self.assertEqual(len(queue.pending()), 1)

    def test_queue_round_trip_and_append(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "intake.json"
            queue = create_intake_queue(path, "phone-intake")
            queue.add("first", source_kind="note", language="English", packet_id="p1")
            save_intake_queue(queue, path)
            loaded = load_intake_queue(path)
            loaded.add("second", source_kind="chat_exchange", language="undetermined", packet_id="p2")
            save_intake_queue(loaded, path)
            reread = load_intake_queue(path)
            self.assertEqual([p["packet_id"] for p in reread.pending()], ["p1", "p2"])

    def test_invalid_packet_does_not_append(self):
        queue = WorkIntakeQueue("queue")
        before = queue.to_snapshot()
        with self.assertRaises(IntakeQueueError):
            queue.add("text", source_kind="chat", language="")
        self.assertEqual(queue.to_snapshot(), before)

    def test_duplicate_packet_identity_rejected(self):
        queue = WorkIntakeQueue("queue")
        queue.add("one", source_kind="note", language="English", packet_id="same")
        with self.assertRaisesRegex(IntakeQueueError, "duplicate"):
            queue.add("two", source_kind="note", language="English", packet_id="same")

    def test_tampered_placement_state_rejected(self):
        queue = WorkIntakeQueue("queue")
        queue.add("one", source_kind="note", language="English", packet_id="p1")
        snapshot = queue.to_snapshot()
        snapshot["packets"][0]["placement_status"] = "placed"
        with self.assertRaisesRegex(IntakeQueueError, "unsupported placement status"):
            WorkIntakeQueue.from_snapshot(snapshot)

    def test_import_is_idempotent_after_audited_save_before_queue_update(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            queue_path = root / "intake.json"
            store_path = root / "audited.json"
            queue = create_intake_queue(queue_path, "phone-intake")
            packet = queue.add(
                {"text": "Preserve this exact source.", "language": "English"},
                source_kind="chat_exchange", source_reference="checkpoint-9",
                language="English", metadata={"topic": "continuity"},
                captured_at="2026-11-17T16:00:00Z", packet_id="packet-9",
            )
            save_intake_queue(queue, queue_path)

            audited = AuditedMemoryStore("test-phone")
            audited.add_tree_location("roots.archive", "roots", "Living Archive")
            audited.add_tree_location(
                "branches.project", "branches", "Project",
                parent_id="roots.archive",
            )
            save_audited(audited, store_path)

            # First stage succeeds: audited store is saved, but queue remains pending.
            first_store = load_audited(store_path)
            first = first_store.import_intake_packet(
                packet, queue_id=queue.queue_id,
                primary_node_id="roots.archive",
                contextual_node_ids=["branches.project"],
            )
            save_audited(first_store, store_path)

            # Simulate interruption before queue.mark_imported/save_intake_queue.
            retry_store = load_audited(store_path)
            retry = retry_store.import_intake_packet(
                packet, queue_id=queue.queue_id,
                primary_node_id="roots.archive",
                contextual_node_ids=["branches.project"],
            )
            self.assertEqual(retry.record_id, first.record_id)
            self.assertEqual(retry.version_id, first.version_id)
            self.assertEqual(len(retry_store.versions(first.record_id)), 1)
            self.assertEqual(len(retry_store.events), 1)

            queue.mark_imported(
                "packet-9", primary_logical_location="roots.archive",
                contextual_logical_locations=["branches.project"],
                record_id=retry.record_id, version_id=retry.version_id,
            )
            save_intake_queue(queue, queue_path)
            reread_queue = load_intake_queue(queue_path)
            self.assertEqual(len(reread_queue.pending()), 0)
            self.assertEqual(reread_queue.packets[0]["placement_status"], "imported")
            self.assertEqual(reread_queue.packets[0]["imported_record_id"], first.record_id)
            final_store = load_audited(store_path)
            self.assertEqual(final_store.get_version(first.version_id).content, packet["content"])
            self.assertEqual(
                [(p.node_id, p.role) for p in final_store.placements_for(first.record_id)],
                [("roots.archive", "primary"), ("branches.project", "contextual")],
            )

    def test_retry_rejects_changed_packet_or_changed_placement(self):
        audited = AuditedMemoryStore("test-phone")
        audited.add_tree_location("roots.archive", "roots", "Living Archive")
        audited.add_tree_location("branches.project", "branches", "Project")
        queue = WorkIntakeQueue("phone-intake")
        packet = queue.add(
            "original", source_kind="note", language="English", packet_id="same-packet",
            captured_at="2026-11-17T16:00:00Z",
        )
        audited.import_intake_packet(packet, queue_id=queue.queue_id, primary_node_id="roots.archive")
        changed = dict(packet)
        changed["content"] = "changed after import"
        with self.assertRaisesRegex(ValueError, "content or metadata changed"):
            audited.import_intake_packet(changed, queue_id=queue.queue_id, primary_node_id="roots.archive")
        with self.assertRaisesRegex(ValueError, "different Tree placement"):
            audited.import_intake_packet(packet, queue_id=queue.queue_id, primary_node_id="branches.project")

    def test_mark_imported_is_idempotent_but_rejects_conflicting_identity(self):
        queue = WorkIntakeQueue("queue")
        queue.add("one", source_kind="note", language="English", packet_id="p1")
        args = dict(
            primary_logical_location="roots.archive",
            contextual_logical_locations=[],
            record_id="record-1", version_id="version-1",
        )
        first = queue.mark_imported("p1", **args)
        again = queue.mark_imported("p1", **args)
        self.assertEqual(first, again)
        with self.assertRaisesRegex(IntakeQueueError, "different identities"):
            queue.mark_imported("p1", **{**args, "record_id": "record-2"})

    def test_init_refuses_overwrite(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "intake.json"
            create_intake_queue(path, "queue")
            with self.assertRaisesRegex(FileExistsError, "refusing to overwrite"):
                create_intake_queue(path, "other")


if __name__ == "__main__":
    unittest.main()
