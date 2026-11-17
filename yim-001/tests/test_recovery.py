import json
import tempfile
import unittest
from pathlib import Path

from yim001 import AuditedMemoryStore, load_audited, save_audited
from yim001.recovery import (
    RecoveryError,
    create_recovery_backup,
    inspect_recovery_backup,
    load_recovery_backup,
    restore_recovery_backup,
    restore_recovery_backup_file,
    save_recovery_backup,
)


class RecoveryBackupTests(unittest.TestCase):
    def setUp(self):
        self.source = AuditedMemoryStore("source-actor")
        self.first = self.source.create_record(
            {"generation": 1}, timestamp="2026-11-10T12:00:00Z"
        )
        self.backup = create_recovery_backup(
            self.source, "source-instance",
            created_at="2026-11-10T12:05:00Z",
        )

    def test_backup_round_trip_is_detached_and_validated(self):
        checked = inspect_recovery_backup(self.backup)
        self.assertEqual(checked["audited_snapshot"], self.source.to_snapshot())
        checked["audited_snapshot"]["actor_id"] = "changed-copy"
        self.assertEqual(self.backup["payload"]["audited_snapshot"], self.source.to_snapshot())

    def test_backup_rejects_tampering(self):
        damaged = json.loads(json.dumps(self.backup))
        damaged["payload"]["audited_snapshot"]["actor_id"] = "forged"
        with self.assertRaisesRegex(RecoveryError, "digest mismatch"):
            inspect_recovery_backup(damaged)

    def test_backup_rejects_missing_timezone(self):
        with self.assertRaisesRegex(RecoveryError, "UTC offset"):
            create_recovery_backup(self.source, "source-instance", created_at="2026-11-10T12:05:00")

    def test_saved_backup_is_exclusive_and_loads(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "backup.json"
            save_recovery_backup(self.backup, path)
            loaded = load_recovery_backup(path)
            self.assertEqual(inspect_recovery_backup(loaded), inspect_recovery_backup(self.backup))
            before = path.read_bytes()
            with self.assertRaises(FileExistsError):
                save_recovery_backup(self.backup, path)
            self.assertEqual(path.read_bytes(), before)

    def test_restore_plan_is_non_mutating_and_explicit_about_whole_store_scope(self):
        from yim001.recovery import create_recovery_restore_plan
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            backup_path = root / "backup.json"
            destination = root / "memory.json"
            preservation = root / "before.bin"
            save_recovery_backup(self.backup, backup_path)
            destination.write_bytes(b"existing destination bytes")
            before = destination.read_bytes()
            plan = create_recovery_restore_plan(
                backup_path, self.first.record_id, destination,
                preserve_path=preservation,
            )
            self.assertEqual(plan["status"], "plan_ready_pending_authorization")
            self.assertTrue(plan["source"]["validated"])
            self.assertEqual(plan["source"]["backup_sha256"], self.backup["sha256"])
            self.assertTrue(plan["requested_record"]["present_in_validated_backup"])
            self.assertIn(self.first.version_id, plan["requested_record"]["version_ids"])
            self.assertEqual(plan["restore_scope"], "entire_audited_store_snapshot")
            self.assertTrue(plan["restore_will_replace_destination_store"])
            self.assertTrue(plan["authorization_required"])
            self.assertFalse(plan["authorized"])
            self.assertTrue(plan["plan_is_not_a_restore"])
            self.assertEqual(plan["destination"]["sha256_when_planned"],
                             __import__("hashlib").sha256(before).hexdigest())
            self.assertEqual(destination.read_bytes(), before)
            self.assertFalse(preservation.exists())

    def test_restore_plan_rejects_missing_record_and_bad_preservation_state(self):
        from yim001.recovery import create_recovery_restore_plan
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            backup_path = root / "backup.json"
            destination = root / "memory.json"
            save_recovery_backup(self.backup, backup_path)
            with self.assertRaisesRegex(RecoveryError, "does not contain"):
                create_recovery_restore_plan(
                    backup_path, "missing-record", destination
                )
            destination.write_bytes(b"old")
            with self.assertRaisesRegex(RecoveryError, "preserve_path is required"):
                create_recovery_restore_plan(
                    backup_path, self.first.record_id, destination
                )
            preservation = root / "already-there.bin"
            preservation.write_bytes(b"keep")
            with self.assertRaisesRegex(RecoveryError, "already exists"):
                create_recovery_restore_plan(
                    backup_path, self.first.record_id, destination,
                    preserve_path=preservation,
                )
            self.assertEqual(destination.read_bytes(), b"old")
            self.assertEqual(preservation.read_bytes(), b"keep")

    def test_plan_revalidation_detects_changed_source_and_destination(self):
        from yim001.recovery import create_recovery_restore_plan, revalidate_recovery_restore_plan
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            backup_path = root / "backup.json"
            destination = root / "memory.json"
            preservation = root / "before.bin"
            save_recovery_backup(self.backup, backup_path)
            destination.write_bytes(b"original destination")
            plan = create_recovery_restore_plan(
                backup_path, self.first.record_id, destination,
                preserve_path=preservation,
            )
            self.assertEqual(
                revalidate_recovery_restore_plan(plan)["status"],
                "valid_pending_authorization",
            )
            destination.write_bytes(b"changed destination")
            with self.assertRaisesRegex(RecoveryError, "destination contents changed"):
                revalidate_recovery_restore_plan(plan)
            destination.write_bytes(b"original destination")
            backup_path.write_bytes(b"{}")
            with self.assertRaisesRegex(RecoveryError, "source backup"):
                revalidate_recovery_restore_plan(plan)

    def test_plan_revalidation_detects_new_destination_and_preservation_collision(self):
        from yim001.recovery import create_recovery_restore_plan, revalidate_recovery_restore_plan
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            backup_path = root / "backup.json"
            destination = root / "memory.json"
            save_recovery_backup(self.backup, backup_path)
            plan = create_recovery_restore_plan(
                backup_path, self.first.record_id, destination
            )
            destination.write_bytes(b"appeared after planning")
            with self.assertRaisesRegex(RecoveryError, "existence changed"):
                revalidate_recovery_restore_plan(plan)
            destination.unlink()
            plan = create_recovery_restore_plan(
                backup_path, self.first.record_id, destination
            )
            self.assertEqual(
                revalidate_recovery_restore_plan(plan)["status"],
                "valid_pending_authorization",
            )
            preservation = root / "collision.bin"
            preservation.write_bytes(b"do not replace")
            destination.write_bytes(b"existing destination")
            plan_with_preservation = create_recovery_restore_plan(
                backup_path, self.first.record_id, destination,
                preserve_path=root / "reserved.bin",
            )
            Path(plan_with_preservation["destination"]["preserve_path"]).write_bytes(b"occupied")
            with self.assertRaisesRegex(RecoveryError, "preservation path is unavailable"):
                revalidate_recovery_restore_plan(plan_with_preservation)
            self.assertEqual(preservation.read_bytes(), b"do not replace")

    def test_restore_plan_requires_authorization_and_revalidates_before_write(self):
        from yim001.recovery import create_recovery_restore_plan, restore_recovery_plan
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            backup_path = root / "backup.json"
            destination = root / "memory.json"
            preservation = root / "before.bin"
            save_recovery_backup(self.backup, backup_path)
            destination.write_bytes(b"old destination")
            plan = create_recovery_restore_plan(
                backup_path, self.first.record_id, destination,
                preserve_path=preservation,
            )
            with self.assertRaisesRegex(RecoveryError, "authorization"):
                restore_recovery_plan(plan, authorized=False)
            self.assertEqual(destination.read_bytes(), b"old destination")
            destination.write_bytes(b"changed after plan")
            with self.assertRaisesRegex(RecoveryError, "destination contents changed"):
                restore_recovery_plan(plan, authorized=True)
            self.assertEqual(destination.read_bytes(), b"changed after plan")
            self.assertFalse(preservation.exists())

    def test_restore_plan_executes_only_after_revalidation_and_preserves_destination(self):
        from yim001.recovery import create_recovery_restore_plan, restore_recovery_plan
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            backup_path = root / "backup.json"
            destination = root / "memory.json"
            preservation = root / "before.bin"
            save_recovery_backup(self.backup, backup_path)
            destination.write_bytes(b"old destination bytes")
            plan = create_recovery_restore_plan(
                backup_path, self.first.record_id, destination,
                preserve_path=preservation,
            )
            result = restore_recovery_plan(plan, authorized=True)
            self.assertEqual(result["status"], "restored")
            self.assertEqual(preservation.read_bytes(), b"old destination bytes")
            restored = load_audited(destination)
            self.assertEqual(restored.to_snapshot()["memory"], self.source.to_snapshot()["memory"])
            self.assertEqual(restored.events[-1].event_type, "snapshot.recovered")

    def test_unauthorized_restore_fails_before_file_access(self):
        with tempfile.TemporaryDirectory() as directory:
            missing = Path(directory) / "missing.json"
            with self.assertRaisesRegex(RecoveryError, "authorization"):
                restore_recovery_backup(
                    self.backup, missing, authorized=False
                )
            self.assertFalse(missing.exists())

    def test_restore_existing_snapshot_preserves_exact_prior_bytes(self):
        with tempfile.TemporaryDirectory() as directory:
            destination = Path(directory) / "memory.json"
            preservation = Path(directory) / "memory.pre-restore.bin"
            destination.write_bytes(b'{"damaged":NaN, "raw": "keep exactly"}')
            original = destination.read_bytes()
            result = restore_recovery_backup(
                self.backup, destination, authorized=True,
                preserve_path=preservation,
            )
            self.assertEqual(preservation.read_bytes(), original)
            restored = load_audited(destination)
            self.assertEqual(restored.to_snapshot()["memory"], self.source.to_snapshot()["memory"])
            self.assertEqual(restored.to_snapshot()["living_tree"], self.source.to_snapshot()["living_tree"])
            recovery_event = restored.events[-1]
            self.assertEqual(recovery_event.event_type, "snapshot.recovered")
            self.assertEqual(recovery_event.details["recovery_kind"], "restored_backup_snapshot")
            self.assertTrue(recovery_event.details["original_record_identities_preserved"])
            self.assertFalse(recovery_event.details["reconstruction"])
            self.assertEqual(recovery_event.details["backup_sha256"], self.backup["sha256"])
            self.assertTrue(result["previous_destination_preserved"])

    def test_restore_refuses_existing_destination_without_preservation_path(self):
        with tempfile.TemporaryDirectory() as directory:
            destination = Path(directory) / "memory.json"
            destination.write_text("not-json", encoding="utf-8")
            original = destination.read_bytes()
            with self.assertRaisesRegex(RecoveryError, "preserve_path"):
                restore_recovery_backup(self.backup, destination, authorized=True)
            self.assertEqual(destination.read_bytes(), original)

    def test_restore_refuses_to_overwrite_preservation_artifact(self):
        with tempfile.TemporaryDirectory() as directory:
            destination = Path(directory) / "memory.json"
            preservation = Path(directory) / "prior.bin"
            destination.write_text("corrupt", encoding="utf-8")
            preservation.write_bytes(b"do not overwrite")
            with self.assertRaises(FileExistsError):
                restore_recovery_backup(
                    self.backup, destination, authorized=True,
                    preserve_path=preservation,
                )
            self.assertEqual(destination.read_text(encoding="utf-8"), "corrupt")
            self.assertEqual(preservation.read_bytes(), b"do not overwrite")

    def test_failed_restore_write_keeps_destination_and_preservation(self):
        from unittest.mock import patch
        with tempfile.TemporaryDirectory() as directory:
            destination = Path(directory) / "memory.json"
            preservation = Path(directory) / "before-restore.bin"
            destination.write_bytes(b'{"generation": "old"}')
            original = destination.read_bytes()
            with patch("yim001.audited.write_json_atomic", side_effect=OSError("simulated replace failure")):
                with self.assertRaisesRegex(OSError, "simulated replace failure"):
                    restore_recovery_backup(
                        self.backup, destination, authorized=True,
                        preserve_path=preservation,
                    )
            self.assertEqual(destination.read_bytes(), original)
            self.assertEqual(preservation.read_bytes(), original)

    def test_restore_missing_destination_creates_validated_snapshot(self):
        with tempfile.TemporaryDirectory() as directory:
            destination = Path(directory) / "new-memory.json"
            result = restore_recovery_backup(
                self.backup, destination, authorized=True
            )
            self.assertFalse(result["previous_destination_existed"])
            restored = load_audited(destination)
            self.assertEqual(restored.to_snapshot()["memory"], self.source.to_snapshot()["memory"])
            self.assertEqual(restored.events[-1].event_type, "snapshot.recovered")

    def test_restore_file_helper_checks_authorization_before_read(self):
        with tempfile.TemporaryDirectory() as directory:
            missing_backup = Path(directory) / "missing-backup.json"
            destination = Path(directory) / "destination.json"
            with self.assertRaisesRegex(RecoveryError, "authorization"):
                restore_recovery_backup_file(
                    missing_backup, destination, authorized=False
                )
            self.assertFalse(destination.exists())

    def test_restore_file_helper_loads_and_restores(self):
        with tempfile.TemporaryDirectory() as directory:
            backup_path = Path(directory) / "backup.json"
            destination = Path(directory) / "destination.json"
            save_recovery_backup(self.backup, backup_path)
            save_audited(AuditedMemoryStore("old-destination"), destination)
            preservation = Path(directory) / "old-destination.bin"
            result = restore_recovery_backup_file(
                backup_path, destination, authorized=True,
                preserve_path=preservation,
            )
            self.assertTrue(result["previous_destination_preserved"])
            restored = load_audited(destination)
            self.assertEqual(restored.to_snapshot()["memory"], self.source.to_snapshot()["memory"])
            self.assertEqual(restored.to_snapshot()["living_tree"], self.source.to_snapshot()["living_tree"])
            self.assertEqual(restored.events[-1].event_type, "snapshot.recovered")
            self.assertTrue(preservation.exists())


if __name__ == "__main__":
    unittest.main()
