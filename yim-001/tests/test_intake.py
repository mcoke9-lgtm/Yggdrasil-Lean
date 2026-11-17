import json
import tempfile
import unittest
from pathlib import Path

from yim001.intake import (
    IntakeQueueError, WorkIntakeQueue, create_intake_queue,
    load_intake_queue, save_intake_queue,
)


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

    def test_init_refuses_overwrite(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "intake.json"
            create_intake_queue(path, "queue")
            with self.assertRaisesRegex(FileExistsError, "refusing to overwrite"):
                create_intake_queue(path, "other")


if __name__ == "__main__":
    unittest.main()
