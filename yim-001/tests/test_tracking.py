import hashlib
import json
import tempfile
import unittest
from pathlib import Path

from yim001.tracking import (
    TrackingRegister, TrackingRegisterError, load_register, save_register,
)

STAMP = "2026-11-27T12:00:00Z"


class TrackingRegisterTests(unittest.TestCase):
    def make_register(self):
        register = TrackingRegister("living-tree-tracking")
        register.observe(
            tracking_id="news-001", source_title="European relations article",
            source_url="https://example.test/article", publisher="Example",
            published_at=None, captured_at=STAMP, user_note=None,
            keywords=["Europe", "diplomacy"], event_id="evt-1",
            actor_id="human", timestamp=STAMP,
        )
        return register

    def test_observation_is_not_assessment_or_endorsement(self):
        row = self.make_register().items()[0]
        self.assertEqual(row["state"], "Observed")
        self.assertIsNone(row["published_at"])
        self.assertIsNone(row["user_note"])
        self.assertNotIn("latest_assessment", row)
        self.assertNotIn("adoption", row)

    def test_tracking_and_updates_append_without_overwriting_observation(self):
        register = self.make_register()
        register.start_tracking(tracking_id="news-001", event_id="evt-2",
                                actor_id="human", timestamp=STAMP)
        register.capture_update(
            tracking_id="news-001", update_note="A later source update",
            source_ref="https://example.test/update", captured_at=STAMP,
            event_id="evt-3", actor_id="collector", timestamp=STAMP,
        )
        row = register.items()[0]
        self.assertEqual(row["state"], "Tracking")
        self.assertEqual(row["source_title"], "European relations article")
        self.assertEqual(len(row["updates"]), 1)
        self.assertEqual(len(register.events), 3)

    def test_assessment_does_not_automatically_adopt(self):
        register = self.make_register()
        register.assess(
            tracking_id="news-001", assessment="Claims reviewed",
            assessed_by="reviewer", evidence_refs=["source-a"],
            uncertainty="Some details remain unverified",
            event_id="evt-2", actor_id="reviewer", timestamp=STAMP,
        )
        row = register.items()[0]
        self.assertEqual(row["state"], "Assessed")
        self.assertNotIn("adoption", row)

    def test_adoption_requires_assessment_and_explicit_authorization(self):
        register = self.make_register()
        with self.assertRaisesRegex(TrackingRegisterError, "requires an assessed item"):
            register.authorize_adoption(
                tracking_id="news-001", target="principle-1", decision="incorporate",
                authorized_by="human", authorization_ref="approval-1",
                explicitly_authorized=True, event_id="evt-2", actor_id="operator",
                timestamp=STAMP,
            )
        register.assess(
            tracking_id="news-001", assessment="Reviewed",
            assessed_by="reviewer", evidence_refs=[], uncertainty="Limited",
            event_id="evt-2", actor_id="reviewer", timestamp=STAMP,
        )
        with self.assertRaisesRegex(TrackingRegisterError, "explicit authorization"):
            register.authorize_adoption(
                tracking_id="news-001", target="principle-1", decision="incorporate",
                authorized_by="human", authorization_ref="approval-1",
                explicitly_authorized=False, event_id="evt-3", actor_id="operator",
                timestamp=STAMP,
            )
        register.authorize_adoption(
            tracking_id="news-001", target="principle-1", decision="incorporate",
            authorized_by="human", authorization_ref="approval-2",
            explicitly_authorized=True, event_id="evt-4", actor_id="operator",
            timestamp=STAMP,
        )
        self.assertEqual(register.items()[0]["state"], "Adopted")

    def test_correction_is_appended_and_original_event_remains(self):
        register = self.make_register()
        register.append_correction(
            tracking_id="news-001", target_event_id="evt-1",
            correction="Publisher label corrected", source_ref="source-proof",
            event_id="evt-2", actor_id="reviewer", timestamp=STAMP,
        )
        row = register.items()[0]
        self.assertEqual(row["source_title"], "European relations article")
        self.assertEqual(row["corrections"][0]["target_event_id"], "evt-1")
        self.assertEqual([event["event_id"] for event in register.events], ["evt-1", "evt-2"])

    def test_save_load_restart_preserves_history_and_hash_chain(self):
        register = self.make_register()
        register.start_tracking(tracking_id="news-001", event_id="evt-2",
                                actor_id="human", timestamp=STAMP)
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "tracking.json"
            save_register(register, path)
            recovered = load_register(path)
            self.assertEqual(recovered.to_snapshot(), register.to_snapshot())
            self.assertEqual(recovered.items()[0]["state"], "Tracking")

    def test_tampered_payload_and_broken_chain_are_rejected(self):
        snapshot = self.make_register().to_snapshot()
        snapshot["events"][0]["payload"]["source_title"] = "Altered"
        with self.assertRaisesRegex(TrackingRegisterError, "hash mismatch"):
            TrackingRegister.from_snapshot(snapshot)
        snapshot = self.make_register().to_snapshot()
        snapshot["events"][0]["previous_event_hash"] = "wrong"
        with self.assertRaisesRegex(TrackingRegisterError, "history link mismatch"):
            TrackingRegister.from_snapshot(snapshot)

    def test_invalid_transition_and_duplicate_identity_are_rejected(self):
        register = self.make_register()
        register.start_tracking(tracking_id="news-001", event_id="evt-2",
                                actor_id="human", timestamp=STAMP)
        with self.assertRaisesRegex(TrackingRegisterError, "only once from Observed"):
            register.start_tracking(tracking_id="news-001", event_id="evt-3",
                                    actor_id="human", timestamp=STAMP)
        with self.assertRaisesRegex(TrackingRegisterError, "duplicate tracking_id"):
            register.observe(
                tracking_id="news-001", source_title="Duplicate",
                event_id="evt-4", actor_id="human", timestamp=STAMP,
                captured_at=STAMP,
            )

    def test_source_recovery_is_explicit_and_append_only(self):
        register = self.make_register()
        self.assertEqual(register.items()[0]["source_recovery_status"], "unknown")
        register.record_source_recovery(
            tracking_id="news-001", status="unavailable",
            retrieval_notes="Publisher page returned an access error",
            source_ref="https://example.test/article",
            event_id="evt-2", actor_id="retriever", timestamp=STAMP,
        )
        register.record_source_recovery(
            tracking_id="news-001", status="partial",
            retrieval_notes="Headline and date recovered from archive",
            source_ref="https://archive.example.test/article",
            event_id="evt-3", actor_id="retriever", timestamp=STAMP,
        )
        row = register.items()[0]
        self.assertEqual(row["source_recovery_status"], "partial")
        self.assertEqual(len(row["source_recovery_history"]), 2)
        self.assertEqual(len(register.events), 3)

    def test_search_preserves_state_and_item_event_history(self):
        register = self.make_register()
        register.start_tracking(tracking_id="news-001", event_id="evt-2",
                                actor_id="human", timestamp=STAMP)
        found = register.search("europe", states=["Tracking"])
        self.assertEqual(len(found), 1)
        self.assertEqual(found[0]["state"], "Tracking")
        self.assertEqual([e["event_id"] for e in found[0]["event_history"]],
                         ["evt-1", "evt-2"])
        self.assertEqual(register.search("europe", states=["Observed"]), [])

    def test_export_preserves_complete_lifecycle_event_history(self):
        register = self.make_register()
        register.start_tracking(tracking_id="news-001", event_id="evt-2",
                                actor_id="human", timestamp=STAMP)
        exported = register.export_json()
        recovered = TrackingRegister.from_snapshot(__import__("json").loads(exported))
        self.assertEqual(recovered.to_snapshot(), register.to_snapshot())
        self.assertEqual(len(recovered.events), 2)
        self.assertEqual(recovered.items()[0]["state"], "Tracking")

    def test_version_one_snapshot_migrates_without_losing_event_history(self):
        snapshot = self.make_register().to_snapshot()
        snapshot["format_version"] = 1
        payload = snapshot["events"][0]["payload"]
        payload.pop("source_recovery_status")
        payload.pop("retrieval_notes")
        event = snapshot["events"][0]
        core = {key: value for key, value in event.items() if key != "event_hash"}
        canonical = json.dumps(
            core, ensure_ascii=False, allow_nan=False, sort_keys=True,
            separators=(",", ":"),
        ).encode("utf-8")
        event["event_hash"] = hashlib.sha256(canonical).hexdigest()

        migrated = TrackingRegister.from_snapshot(snapshot)
        self.assertEqual(migrated.items()[0]["source_recovery_status"], "unknown")
        self.assertEqual(migrated.events[0]["event_id"], "evt-1")
        self.assertEqual(migrated.to_snapshot()["format_version"], 3)

    def test_malformed_snapshot_types_raise_register_error(self):
        snapshot = self.make_register().to_snapshot()
        snapshot["events"][0]["event_type"] = []
        with self.assertRaises(TrackingRegisterError):
            TrackingRegister.from_snapshot(snapshot)

    def test_snapshot_hash_binds_registry_identity_and_events(self):
        snapshot = self.make_register().to_snapshot()
        snapshot["registry_id"] = "different-registry"
        with self.assertRaisesRegex(TrackingRegisterError, "snapshot integrity hash mismatch"):
            TrackingRegister.from_snapshot(snapshot)

    def test_search_includes_event_actor_and_timestamp_metadata(self):
        register = self.make_register()
        register.start_tracking(
            tracking_id="news-001", event_id="evt-2",
            actor_id="archivist-special", timestamp=STAMP,
        )
        found = register.search("archivist-special")
        self.assertEqual(len(found), 1)
        self.assertEqual(found[0]["event_history"][-1]["actor_id"], "archivist-special")

    def test_initial_recovery_status_is_in_recovery_history_projection(self):
        register = self.make_register()
        history = register.items()[0]["source_recovery_history"]
        self.assertEqual(len(history), 1)
        self.assertEqual(history[0]["status"], "unknown")
        self.assertEqual(history[0]["event_id"], "evt-1")
        self.assertEqual(history[0]["event_type"], "item.observed")

    def test_timezone_is_required(self):
        register = TrackingRegister("test")
        with self.assertRaisesRegex(TrackingRegisterError, "include a timezone"):
            register.observe(
                tracking_id="bad", source_title="Bad timestamp",
                event_id="evt-1", actor_id="human", timestamp="2026-11-27T12:00:00",
                captured_at=STAMP,
            )


if __name__ == "__main__":
    unittest.main()
