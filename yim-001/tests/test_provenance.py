import copy
import unittest

from yim001.provenance import EventLedger, ProvenanceError, GENESIS


class EventLedgerTests(unittest.TestCase):
    def test_append_builds_monotonic_hash_linked_events(self):
        ledger = EventLedger()
        first = ledger.append("record.created", "system-a", record_id="r1", version_id="v1", timestamp="2026-11-10T12:00:00Z")
        second = ledger.append("record.revised", "system-a", record_id="r1", version_id="v2", details={"source": "cli"}, timestamp="2026-11-10T12:01:00+00:00")
        self.assertEqual(first.sequence, 0)
        self.assertEqual(first.previous_digest, GENESIS)
        self.assertEqual(second.sequence, 1)
        self.assertEqual(second.previous_digest, first.digest)
        self.assertNotEqual(first.digest, second.digest)

    def test_round_trip_preserves_ledger(self):
        ledger = EventLedger()
        ledger.append("record.created", "node-1", record_id="r", version_id="v", details={"nested": [1, True]}, timestamp="2026-11-10T12:00:00Z")
        ledger.append("record.exported", "node-1", record_id="r", version_id="v", timestamp="2026-11-10T12:01:00Z")
        restored = EventLedger.from_snapshot(ledger.to_snapshot())
        self.assertEqual(restored.to_snapshot(), ledger.to_snapshot())

    def test_tampered_details_are_detected(self):
        ledger = EventLedger()
        ledger.append("record.created", "node-1", record_id="r", timestamp="2026-11-10T12:00:00Z")
        snapshot = ledger.to_snapshot()
        snapshot["events"][0]["details"] = {"changed": True}
        with self.assertRaisesRegex(ProvenanceError, "digest mismatch"):
            EventLedger.from_snapshot(snapshot)

    def test_reordered_or_missing_sequence_is_rejected(self):
        ledger = EventLedger()
        ledger.append("record.created", "node-1", timestamp="2026-11-10T12:00:00Z")
        ledger.append("record.revised", "node-1", timestamp="2026-11-10T12:01:00Z")
        snapshot = ledger.to_snapshot()
        snapshot["events"][1]["sequence"] = 4
        with self.assertRaisesRegex(ProvenanceError, "sequence"):
            EventLedger.from_snapshot(snapshot)

    def test_broken_chain_is_rejected(self):
        ledger = EventLedger()
        ledger.append("record.created", "node-1", timestamp="2026-11-10T12:00:00Z")
        ledger.append("record.revised", "node-1", timestamp="2026-11-10T12:01:00Z")
        snapshot = ledger.to_snapshot()
        snapshot["events"][1]["previous_digest"] = "f" * 64
        with self.assertRaisesRegex(ProvenanceError, "previous digest"):
            EventLedger.from_snapshot(snapshot)

    def test_duplicate_event_identity_is_rejected(self):
        ledger = EventLedger()
        ledger.append("record.created", "node-1", timestamp="2026-11-10T12:00:00Z", event_id="same")
        with self.assertRaisesRegex(ProvenanceError, "duplicate event_id"):
            ledger.append("record.revised", "node-1", timestamp="2026-11-10T12:01:00Z", event_id="same")

    def test_timezone_is_required(self):
        ledger = EventLedger()
        with self.assertRaisesRegex(ProvenanceError, "UTC offset"):
            ledger.append("record.created", "node-1", timestamp="2026-11-10T12:00:00")

    def test_unknown_event_type_is_rejected(self):
        with self.assertRaisesRegex(ProvenanceError, "unsupported event type"):
            EventLedger().append("record.deleted", "node-1")

    def test_non_json_details_are_rejected_without_mutation(self):
        ledger = EventLedger()
        with self.assertRaises(ValueError):
            ledger.append("record.created", "node-1", details={"bad": float("nan")})
        self.assertEqual(ledger.events, ())

    def test_boolean_sequence_is_rejected(self):
        ledger = EventLedger()
        ledger.append("record.created", "node-1", timestamp="2026-11-10T12:00:00Z")
        snapshot = ledger.to_snapshot()
        snapshot["events"][0]["sequence"] = True
        with self.assertRaisesRegex(ProvenanceError, "sequence"):
            EventLedger.from_snapshot(snapshot)


if __name__ == "__main__":
    unittest.main()
