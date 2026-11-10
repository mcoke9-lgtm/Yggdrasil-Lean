import copy
import unittest

from yim001 import create_recovery_backup, AuditedMemoryStore
from yim001.checkpoints import (
    CheckpointError,
    create_checkpoint_record,
    inspect_checkpoint_record,
    verify_checkpoint_chain,
)


class RecoveryCheckpointTests(unittest.TestCase):
    def setUp(self):
        self.store = AuditedMemoryStore("actor")
        self.store.create_record({"n": 1}, timestamp="2026-11-10T12:00:00Z")
        self.backup1 = create_recovery_backup(
            self.store, "phone-A", created_at="2026-11-10T12:01:00Z"
        )
        self.record1 = create_checkpoint_record(
            self.backup1, 1, recorded_at="2026-11-10T12:02:00Z"
        )
        self.store.create_record({"n": 2}, timestamp="2026-11-10T12:03:00Z")
        self.backup2 = create_recovery_backup(
            self.store, "phone-A", created_at="2026-11-10T12:04:00Z"
        )
        self.record2 = create_checkpoint_record(
            self.backup2, 2,
            previous_checkpoint_sha256=self.record1["sha256"],
            recorded_at="2026-11-10T12:05:00Z",
        )

    def test_record_round_trip_and_digest_validation(self):
        self.assertEqual(inspect_checkpoint_record(self.record1)["sequence"], 1)
        damaged = copy.deepcopy(self.record1)
        damaged["payload"]["sequence"] = 7
        with self.assertRaisesRegex(CheckpointError, "digest mismatch"):
            inspect_checkpoint_record(damaged)

    def test_first_record_rejects_predecessor(self):
        with self.assertRaisesRegex(CheckpointError, "first checkpoint"):
            create_checkpoint_record(
                self.backup1, 1, previous_checkpoint_sha256="a" * 64
            )

    def test_later_record_requires_predecessor(self):
        with self.assertRaisesRegex(CheckpointError, "predecessor"):
            create_checkpoint_record(self.backup2, 2)

    def test_complete_chain_with_anchor_and_backups_is_verified(self):
        result = verify_checkpoint_chain(
            [self.record2, self.record1], [self.backup1, self.backup2],
            expected_latest_sequence=2, expected_source_instance_id="phone-A",
        )
        self.assertEqual(result["status"], "VERIFIED")
        self.assertFalse(result["anchor_authenticated"])
        self.assertEqual(result["verified_records"], 2)

    def test_chain_without_external_anchor_is_uncertain(self):
        result = verify_checkpoint_chain(
            [self.record1, self.record2], [self.backup1, self.backup2]
        )
        self.assertEqual(result["status"], "UNCERTAIN")

    def test_missing_backup_is_incomplete(self):
        result = verify_checkpoint_chain(
            [self.record1, self.record2], [self.backup1],
            expected_latest_sequence=2,
        )
        self.assertEqual(result["status"], "INCOMPLETE")
        self.assertEqual(result["missing_backup_digests"], [self.backup2["sha256"]])

    def test_sequence_gap_is_incomplete(self):
        result = verify_checkpoint_chain(
            [self.record1], [self.backup1], expected_latest_sequence=2
        )
        self.assertEqual(result["status"], "STALE")

    def test_broken_link_is_conflict(self):
        broken = copy.deepcopy(self.record2)
        broken["payload"]["previous_checkpoint_sha256"] = "0" * 64
        import hashlib, json
        canonical = json.dumps(
            broken["payload"], ensure_ascii=False, allow_nan=False,
            sort_keys=True, separators=(",", ":"),
        ).encode("utf-8")
        broken["sha256"] = hashlib.sha256(canonical).hexdigest()
        result = verify_checkpoint_chain(
            [self.record1, broken], [self.backup1, self.backup2],
            expected_latest_sequence=2,
        )
        self.assertEqual(result["status"], "CONFLICT")

    def test_fork_is_conflict(self):
        fork = copy.deepcopy(self.record2)
        fork["payload"]["backup_sha256"] = "f" * 64
        import hashlib, json
        canonical = json.dumps(
            fork["payload"], ensure_ascii=False, allow_nan=False,
            sort_keys=True, separators=(",", ":"),
        ).encode("utf-8")
        fork["sha256"] = hashlib.sha256(canonical).hexdigest()
        result = verify_checkpoint_chain(
            [self.record1, self.record2, fork], [self.backup1, self.backup2],
            expected_latest_sequence=2,
        )
        self.assertEqual(result["status"], "CONFLICT")

    def test_source_identity_mismatch_is_conflict(self):
        result = verify_checkpoint_chain(
            [self.record1, self.record2], [self.backup1, self.backup2],
            expected_latest_sequence=2, expected_source_instance_id="other-phone",
        )
        self.assertEqual(result["status"], "CONFLICT")

    def test_invalid_external_anchor_is_conflict(self):
        result = verify_checkpoint_chain(
            [self.record1], [self.backup1], expected_latest_sequence=True
        )
        self.assertEqual(result["status"], "CONFLICT")


if __name__ == "__main__":
    unittest.main()
