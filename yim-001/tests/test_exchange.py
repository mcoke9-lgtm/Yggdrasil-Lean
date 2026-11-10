import copy
import unittest

from yim001.audited import AuditedMemoryStore
from yim001.exchange import ExchangeError, export_envelope, inspect_envelope, stage_exchange


class ExchangeTests(unittest.TestCase):
    def setUp(self):
        self.source = AuditedMemoryStore("source-actor")
        self.first = self.source.create_record({"value": 1}, timestamp="2026-11-10T12:00:00Z")
        self.second = self.source.revise(
            self.first.record_id, {"value": 2}, timestamp="2026-11-10T12:01:00Z"
        )
        self.source.annotate_conflict(
            self.first.version_id, self.second.version_id, timestamp="2026-11-10T12:02:00Z"
        )
        self.envelope = export_envelope(
            self.source, "source-instance", exported_at="2026-11-10T12:03:00Z"
        )

    def test_export_is_detached_and_source_is_unchanged(self):
        before = self.source.to_snapshot()
        envelope = export_envelope(self.source, "source-instance", exported_at="2026-11-10T12:03:00Z")
        envelope["payload"]["audited_snapshot"]["memory"]["versions"].clear()
        self.assertEqual(self.source.to_snapshot(), before)

    def test_inspection_round_trip_preserves_all_identities_and_direction(self):
        inspected = inspect_envelope(self.envelope)
        memory = inspected["audited_snapshot"]["memory"]
        provenance = inspected["audited_snapshot"]["provenance"]
        self.assertEqual([row["version_id"] for row in memory["versions"]], [self.first.version_id, self.second.version_id])
        self.assertEqual(memory["conflicts"][0], {"source_version_id": self.first.version_id, "target_version_id": self.second.version_id})
        self.assertEqual([row["event_id"] for row in provenance["events"]], [event.event_id for event in self.source.events])

    def test_empty_destination_stages_additions_without_mutation(self):
        destination = AuditedMemoryStore("destination")
        before = destination.to_snapshot()
        report = stage_exchange(self.envelope, destination)
        self.assertEqual(report["status"], "staged_read_only")
        self.assertEqual(report["incoming_version_ids"], [self.first.version_id, self.second.version_id])
        self.assertEqual(report["incoming_event_ids"], [event.event_id for event in self.source.events])
        self.assertEqual(report["incoming_directional_conflicts"], [{"source_version_id": self.first.version_id, "target_version_id": self.second.version_id}])
        self.assertFalse(report["mutated_destination"])
        self.assertEqual(destination.to_snapshot(), before)

    def test_repeated_exchange_is_classified_as_shared_evidence(self):
        destination = AuditedMemoryStore.from_snapshot(self.source.to_snapshot())
        report = stage_exchange(self.envelope, destination)
        self.assertEqual(report["status"], "staged_read_only")
        self.assertEqual(report["shared_version_ids"], [self.first.version_id, self.second.version_id])
        self.assertEqual(report["shared_event_ids"], [event.event_id for event in self.source.events])
        self.assertEqual(report["incoming_version_ids"], [])
        self.assertEqual(report["shared_directional_conflicts"], [{"source_version_id": self.first.version_id, "target_version_id": self.second.version_id}])

    def test_same_version_id_with_different_content_is_collision(self):
        destination = AuditedMemoryStore.from_snapshot(self.source.to_snapshot())
        altered = destination.to_snapshot()
        altered["memory"]["versions"][0]["content"] = {"value": "different"}
        from yim001.core import MemoryStore
        destination = AuditedMemoryStore("destination", store=MemoryStore.from_snapshot(altered))
        report = stage_exchange(self.envelope, destination)
        self.assertEqual(report["status"], "identity_collision")
        self.assertIn(self.first.version_id, report["version_identity_collisions"])
        self.assertEqual(report["incoming_version_ids"], [])

    def test_divergent_histories_are_reported_without_rewriting_parents(self):
        destination = AuditedMemoryStore.from_snapshot(
            AuditedMemoryStore.from_snapshot(self.source.to_snapshot()).to_snapshot()
        )
        # Construct two independent descendants of the shared initial version.
        from yim001.core import MemoryStore
        base = MemoryStore.from_snapshot({
            **self.source.to_snapshot()["memory"],
            "versions": self.source.to_snapshot()["memory"]["versions"][:1],
            "records": [{"record_id": self.first.record_id, "version_ids": [self.first.version_id]}],
            "conflicts": [],
        })
        destination = AuditedMemoryStore("destination", store=base)
        destination.revise(self.first.record_id, {"value": "destination"}, timestamp=None) if False else None
        # Audited wrapper uses the same base graph and adds its own independent branch.
        dest_version = destination.revise(self.first.record_id, {"value": "destination"}, timestamp="2026-11-10T12:04:00Z")
        before = destination.to_snapshot()
        report = stage_exchange(self.envelope, destination)
        self.assertIn(self.first.record_id, report["divergent_shared_record_histories"])
        self.assertEqual(report["incoming_parent_edges"], [{"version_id": self.second.version_id, "parent_version_id": self.first.version_id}])
        self.assertIn(dest_version.version_id, report["destination_history_tips"])
        self.assertIn(self.second.version_id, report["source_history_tips"])
        self.assertEqual(destination.to_snapshot(), before)

    def test_tampered_payload_and_digest_are_rejected(self):
        altered = copy.deepcopy(self.envelope)
        altered["payload"]["source_instance_id"] = "forged"
        with self.assertRaisesRegex(ExchangeError, "digest mismatch"):
            inspect_envelope(altered)

    def test_naive_timestamp_is_rejected(self):
        with self.assertRaisesRegex(ExchangeError, "UTC offset"):
            export_envelope(self.source, "source-instance", exported_at="2026-11-10T12:03:00")

    def test_source_event_stream_is_not_flattened_into_destination(self):
        destination = AuditedMemoryStore("destination")
        report = stage_exchange(self.envelope, destination)
        self.assertEqual(report["source_event_stream"]["source_instance_id"], "source-instance")
        self.assertEqual(report["source_event_stream"]["event_count"], len(self.source.events))
        self.assertEqual(report["source_event_stream"]["first_sequence"], 0)
        self.assertEqual(report["source_event_stream"]["last_sequence"], len(self.source.events) - 1)
        self.assertEqual(destination.events, ())


if __name__ == "__main__":
    unittest.main()
