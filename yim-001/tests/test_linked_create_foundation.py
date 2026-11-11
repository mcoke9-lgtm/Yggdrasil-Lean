import unittest

from yim001.provenance import (
    EventLedger,
    LINKED_COMPLETION_SCHEMA,
    LINKED_INTENT_SCHEMA,
    ProvenanceError,
    _canonical_jcs,
    initial_version_digest,
)


OPERATION_ID = "123e4567-e89b-42d3-a456-426614174000"
DIGEST = "a" * 64


class LinkedCreateFoundationTests(unittest.TestCase):
    def test_jcs_uses_deterministic_rfc8785_bytes(self):
        first = {"z": 1, "a": {"y": True, "x": None}}
        second = {"a": {"x": None, "y": True}, "z": 1}
        expected = b'{"a":{"x":null,"y":true},"z":1}'
        self.assertEqual(_canonical_jcs(first), expected)
        self.assertEqual(_canonical_jcs(second), expected)

    def test_jcs_uses_ecmascript_number_serialization(self):
        self.assertEqual(_canonical_jcs({"n": 333333333.33333329}), b'{"n":333333333.3333333}')
        self.assertEqual(_canonical_jcs({"n": 1e-6}), b'{"n":0.000001}')
        self.assertEqual(_canonical_jcs({"n": 1e-7}), b'{"n":1e-7}')

    def test_jcs_rejects_values_outside_json_canonicalization_domain(self):
        with self.assertRaises((ProvenanceError, ValueError, TypeError)):
            _canonical_jcs({"n": float("nan")})
        with self.assertRaises((ProvenanceError, ValueError, TypeError)):
            _canonical_jcs({"s": "\ud800"})
        with self.assertRaises((ProvenanceError, ValueError, TypeError)):
            _canonical_jcs({"n": 10**400})

    def test_initial_version_digest_has_fixed_test_vector(self):
        self.assertEqual(
            initial_version_digest("r", "v", {"a": 1}, None),
            "2588e91bc094fbab6c28b74b74ba7dc11cff2e8d02dd4656a4f0e7e808131699",
        )

    def test_initial_version_digest_binds_all_envelope_fields(self):
        baseline = initial_version_digest("r", "v", {"a": 1}, {"m": 2})
        self.assertNotEqual(baseline, initial_version_digest("other", "v", {"a": 1}, {"m": 2}))
        self.assertNotEqual(baseline, initial_version_digest("r", "other", {"a": 1}, {"m": 2}))
        self.assertNotEqual(baseline, initial_version_digest("r", "v", {"a": 2}, {"m": 2}))
        self.assertNotEqual(baseline, initial_version_digest("r", "v", {"a": 1}, {"m": 3}))

    def test_intent_event_schema_is_accepted_and_round_trips(self):
        ledger = EventLedger()
        event = ledger.append(
            "record.create_intent",
            "caller-claim",
            record_id="record-1",
            version_id="version-1",
            details={
                "schema": LINKED_INTENT_SCHEMA,
                "operation_id": OPERATION_ID,
                "record_digest": DIGEST,
            },
            timestamp="2026-11-11T12:00:00Z",
            event_id="intent-event-1",
        )
        restored = EventLedger.from_snapshot(ledger.to_snapshot())
        self.assertEqual(restored.events[0].to_dict(), event.to_dict())

    def test_linked_completion_schema_is_accepted_and_legacy_created_remains_valid(self):
        ledger = EventLedger()
        legacy = ledger.append(
            "record.created", "legacy-actor", record_id="old-r", version_id="old-v",
            details={"source": "legacy"}, timestamp="2026-11-11T12:00:00Z",
        )
        completion = ledger.append(
            "record.created", "caller-claim", record_id="record-1", version_id="version-1",
            details={
                "schema": LINKED_COMPLETION_SCHEMA,
                "operation_id": OPERATION_ID,
                "intent_event_id": "intent-event-1",
                "record_digest": DIGEST,
            },
            timestamp="2026-11-11T12:01:00Z",
            event_id="completion-event-1",
        )
        restored = EventLedger.from_snapshot(ledger.to_snapshot())
        self.assertEqual(restored.events[0].to_dict(), legacy.to_dict())
        self.assertEqual(restored.events[1].to_dict(), completion.to_dict())

    def test_intent_schema_rejects_wrong_fields_and_noncanonical_operation_id(self):
        bad_details = [
            {"schema": LINKED_INTENT_SCHEMA, "operation_id": OPERATION_ID, "record_digest": DIGEST, "extra": True},
            {"schema": LINKED_INTENT_SCHEMA, "operation_id": OPERATION_ID.upper(), "record_digest": DIGEST},
            {"schema": LINKED_INTENT_SCHEMA, "operation_id": "not-a-uuid", "record_digest": DIGEST},
            {"schema": LINKED_INTENT_SCHEMA, "operation_id": OPERATION_ID, "record_digest": DIGEST.upper()},
        ]
        for details in bad_details:
            with self.subTest(details=details):
                with self.assertRaises(ProvenanceError):
                    EventLedger().append(
                        "record.create_intent", "actor", record_id="r", version_id="v",
                        details=details, timestamp="2026-11-11T12:00:00Z",
                    )

    def test_intent_requires_actor_and_record_version_identity(self):
        base = {"schema": LINKED_INTENT_SCHEMA, "operation_id": OPERATION_ID, "record_digest": DIGEST}
        for actor, record, version in (("", "r", "v"), ("actor", None, "v"), ("actor", "r", None)):
            with self.subTest(actor=actor, record=record, version=version):
                with self.assertRaises(ProvenanceError):
                    EventLedger().append(
                        "record.create_intent", actor, record_id=record, version_id=version,
                        details=base, timestamp="2026-11-11T12:00:00Z",
                    )

    def test_completion_requires_intent_reference(self):
        with self.assertRaises(ProvenanceError):
            EventLedger().append(
                "record.created", "actor", record_id="r", version_id="v",
                details={
                    "schema": LINKED_COMPLETION_SCHEMA,
                    "operation_id": OPERATION_ID,
                    "record_digest": DIGEST,
                },
                timestamp="2026-11-11T12:00:00Z",
            )


if __name__ == "__main__":
    unittest.main()
