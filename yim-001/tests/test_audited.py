import tempfile
import unittest
from pathlib import Path

from yim001.audited import AuditedMemoryStore, load_audited, save_audited
from yim001.provenance import ProvenanceError


class AuditedMemoryStoreTests(unittest.TestCase):
    def setUp(self):
        self.audited = AuditedMemoryStore("test-node")

    def test_create_and_revise_each_append_one_linked_event(self):
        first = self.audited.create_record({"n": 1}, timestamp="2026-11-10T12:00:00Z")
        second = self.audited.revise(
            first.record_id, {"n": 2}, timestamp="2026-11-10T12:01:00Z"
        )
        events = self.audited.events
        self.assertEqual([event.event_type for event in events], ["record.created", "record.revised"])
        self.assertEqual([event.record_id for event in events], [first.record_id, first.record_id])
        self.assertEqual([event.version_id for event in events], [first.version_id, second.version_id])
        self.assertEqual(events[1].previous_digest, events[0].digest)

    def test_conflict_annotation_is_automatically_recorded(self):
        first = self.audited.create_record("a", timestamp="2026-11-10T12:00:00Z")
        second = self.audited.revise(first.record_id, "b", timestamp="2026-11-10T12:01:00Z")
        conflict = self.audited.annotate_conflict(
            first.version_id, second.version_id, timestamp="2026-11-10T12:02:00Z"
        )
        event = self.audited.events[-1]
        self.assertEqual(event.event_type, "conflict.annotated")
        self.assertEqual(event.record_id, first.record_id)
        self.assertEqual(event.version_id, second.version_id)
        self.assertEqual(event.details, {
            "source_version_id": conflict.source_version_id,
            "target_version_id": conflict.target_version_id,
        })

    def test_failed_memory_operation_changes_neither_memory_nor_ledger(self):
        first = self.audited.create_record("a", timestamp="2026-11-10T12:00:00Z")
        before = self.audited.to_snapshot()
        with self.assertRaises(ValueError):
            self.audited.revise("missing-record", "b", timestamp="2026-11-10T12:01:00Z")
        self.assertEqual(self.audited.to_snapshot(), before)
        self.assertEqual(len(self.audited.versions(first.record_id)), 1)

    def test_failed_event_append_rolls_back_memory_mutation(self):
        first = self.audited.create_record("a", timestamp="2026-11-10T12:00:00Z")
        before = self.audited.to_snapshot()
        with self.assertRaises(ProvenanceError):
            self.audited.revise(first.record_id, "b", timestamp="not-a-timestamp")
        self.assertEqual(self.audited.to_snapshot(), before)
        self.assertEqual(len(self.audited.versions(first.record_id)), 1)

    def test_combined_snapshot_round_trip_preserves_both_streams(self):
        first = self.audited.create_record({"x": 1}, timestamp="2026-11-10T12:00:00Z")
        self.audited.revise(first.record_id, {"x": 2}, timestamp="2026-11-10T12:01:00Z")
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "audited.json"
            save_audited(self.audited, path)
            restored = load_audited(path)
        self.assertEqual(restored.to_snapshot(), self.audited.to_snapshot())
        self.assertEqual(len(restored.events), 2)
        self.assertEqual(len(restored.versions(first.record_id)), 2)

    def test_tampered_combined_provenance_is_rejected(self):
        self.audited.create_record("a", timestamp="2026-11-10T12:00:00Z")
        snapshot = self.audited.to_snapshot()
        snapshot["provenance"]["events"][0]["actor_id"] = "forged"
        with self.assertRaisesRegex(ProvenanceError, "digest mismatch"):
            AuditedMemoryStore.from_snapshot(snapshot)

    def test_presence_reports_loaded_store_scope_without_claiming_deletion(self):
        present = self.audited.create_record("source", timestamp="2026-11-17T14:00:00Z")
        found = self.audited.record_presence(present.record_id)
        self.assertEqual(found["status"], "present_in_loaded_store")
        absent = self.audited.record_presence("record-not-in-this-store")
        self.assertEqual(absent["status"], "not_found_in_loaded_store")
        self.assertIn("does not establish deletion", absent["interpretation"])

    def test_work_capture_atomically_preserves_source_language_and_tree_placement(self):
        self.audited.add_tree_location("roots.archive", "roots", "Living Archive")
        self.audited.add_tree_location(
            "branches.language", "branches", "Language",
            parent_id="roots.archive",
        )
        self.audited.add_tree_location(
            "leaves.transcripts", "leaves", "Transcripts",
            parent_id="branches.language",
        )
        original = {"text": "The original wording stays intact.", "language_note": "English"}
        captured = self.audited.capture_work(
            original,
            primary_node_id="roots.archive",
            contextual_node_ids=["branches.language", "leaves.transcripts"],
            source_kind="chat_exchange",
            source_reference="conversation-checkpoint-001",
            language="English",
            metadata={"topic": "memory continuity"},
            timestamp="2026-11-17T15:00:00Z",
        )
        self.assertEqual(captured.content, original)
        self.assertEqual(captured.metadata["capture"]["language"], "English")
        self.assertTrue(captured.metadata["capture"]["source_content_preserved_as_submitted"])
        self.assertEqual(
            [(p.node_id, p.role) for p in self.audited.placements_for(captured.record_id)],
            [
                ("roots.archive", "primary"),
                ("branches.language", "contextual"),
                ("leaves.transcripts", "contextual"),
            ],
        )
        event = self.audited.events[-1]
        self.assertEqual(event.event_type, "work.captured")
        self.assertEqual(event.record_id, captured.record_id)
        self.assertEqual(event.details["language"], "English")
        self.assertEqual(event.details["primary_logical_location"], "roots.archive")

    def test_failed_work_capture_changes_neither_memory_tree_nor_provenance(self):
        self.audited.add_tree_location("roots.archive", "roots", "Living Archive")
        before = self.audited.to_snapshot()
        with self.assertRaisesRegex(ValueError, "contextual Tree location not found"):
            self.audited.capture_work(
                {"text": "preserve me"},
                primary_node_id="roots.archive",
                contextual_node_ids=["missing.context"],
                source_kind="chat_exchange",
                language="undetermined",
                timestamp="2026-11-17T15:00:00Z",
            )
        self.assertEqual(self.audited.to_snapshot(), before)

    def test_work_capture_requires_explicit_language_and_primary_location(self):
        with self.assertRaisesRegex(ValueError, "language"):
            self.audited.capture_work(
                "text", primary_node_id="missing",
                source_kind="note", language="",
            )
        with self.assertRaisesRegex(ValueError, "primary_node_id"):
            self.audited.capture_work(
                "text", primary_node_id="",
                source_kind="note", language="undetermined",
            )

    def test_intake_retry_returns_same_version_and_rejects_changed_content_or_placement(self):
        self.audited.add_tree_location("roots.archive", "roots", "Living Archive")
        self.audited.add_tree_location(
            "branches.project", "branches", "Project",
            parent_id="roots.archive",
        )
        packet = {
            "packet_id": "packet-1",
            "captured_at": "2026-11-17T15:00:00Z",
            "source_kind": "chat_exchange",
            "source_reference": "session-1",
            "language": "English",
            "content": {"text": "Original source"},
            "metadata": {"topic": "continuity"},
            "placement_status": "awaiting_placement",
        }
        first = self.audited.import_intake_packet(
            packet, queue_id="phone-intake",
            primary_node_id="roots.archive",
            contextual_node_ids=["branches.project"],
        )
        retry = self.audited.import_intake_packet(
            packet, queue_id="phone-intake",
            primary_node_id="roots.archive",
            contextual_node_ids=["branches.project"],
        )
        self.assertEqual((retry.record_id, retry.version_id), (first.record_id, first.version_id))
        self.assertEqual(len(self.audited.versions(first.record_id)), 1)
        changed_content = dict(packet, content={"text": "Altered source"})
        with self.assertRaisesRegex(ValueError, "content or metadata changed"):
            self.audited.import_intake_packet(
                changed_content, queue_id="phone-intake",
                primary_node_id="roots.archive",
                contextual_node_ids=["branches.project"],
            )
        with self.assertRaisesRegex(ValueError, "differs from packet content"):
            self.audited.import_intake_packet(
                packet, queue_id="phone-intake",
                primary_node_id="branches.project",
            )

    def test_actor_identity_is_required(self):
        with self.assertRaisesRegex(ValueError, "actor_id"):
            AuditedMemoryStore(" ")


if __name__ == "__main__":
    unittest.main()
