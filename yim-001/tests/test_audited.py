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

    def test_actor_identity_is_required(self):
        with self.assertRaisesRegex(ValueError, "actor_id"):
            AuditedMemoryStore(" ")


if __name__ == "__main__":
    unittest.main()
