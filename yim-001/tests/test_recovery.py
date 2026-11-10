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
            self.assertEqual(load_audited(destination).to_snapshot(), self.source.to_snapshot())
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

    def test_restore_missing_destination_creates_validated_snapshot(self):
        with tempfile.TemporaryDirectory() as directory:
            destination = Path(directory) / "new-memory.json"
            result = restore_recovery_backup(
                self.backup, destination, authorized=True
            )
            self.assertFalse(result["previous_destination_existed"])
            self.assertEqual(load_audited(destination).to_snapshot(), self.source.to_snapshot())

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
            self.assertEqual(load_audited(destination).to_snapshot(), self.source.to_snapshot())
            self.assertTrue(preservation.exists())


if __name__ == "__main__":
    unittest.main()
