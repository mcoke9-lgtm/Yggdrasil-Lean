import tempfile
import unittest
from pathlib import Path

from yim001 import AuditedMemoryStore, create_recovery_backup, save_recovery_backup
from yim001.checkpoints import create_checkpoint_record
from yim001.recovery_search import search_recovery_locations


class RecoverySearchTests(unittest.TestCase):
    def setUp(self):
        self.store = AuditedMemoryStore("search-test")
        self.version = self.store.create_record({"text": "recoverable"})
        self.backup = create_recovery_backup(self.store, "device-A")

    def test_finds_original_identity_in_explicit_validated_backup(self):
        with tempfile.TemporaryDirectory() as directory:
            backup_path = Path(directory) / "backup.json"
            save_recovery_backup(self.backup, backup_path)
            result = search_recovery_locations(
                self.version.record_id, backup_paths=[backup_path]
            )
            self.assertEqual(result["status"], "original_snapshot_found")
            self.assertEqual(
                result["validated_backup_matches"][0]["version_ids"],
                [self.version.version_id],
            )
            self.assertIn("Only the exact", result["scope_limit"])

    def test_not_found_is_scoped_and_not_deletion(self):
        other = AuditedMemoryStore("other")
        other.create_record({"unrelated": True})
        backup = create_recovery_backup(other, "device-A")
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "backup.json"
            save_recovery_backup(backup, path)
            result = search_recovery_locations(
                self.version.record_id, backup_paths=[path]
            )
            self.assertEqual(result["status"], "not_found_in_searched_locations")
            self.assertIn("does not establish deletion", result["interpretation"])

    def test_lineage_reports_unlocated_requested_member_without_claiming_deletion(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "backup.json"
            save_recovery_backup(self.backup, path)
            result = search_recovery_locations(
                "record-not-in-this-backup", backup_paths=[path]
            )
            lineage = result["lineage"]
            self.assertEqual(lineage["member_status"], [{
                "record_id": "record-not-in-this-backup",
                "status": "not_found_in_supplied_validated_backups",
                "interpretation": (
                    "No supplied validated backup contains this identity. "
                    "Its existence, deletion, or availability elsewhere is not established."
                ),
            }])
            self.assertEqual(lineage["unresolved_relationships"], [{
                "record_id": "record-not-in-this-backup",
                "missing_from_supplied_validated_backups": True,
                "status": "unresolved_evidence_gap",
            }])

    def test_reports_missing_and_inaccessible_locations(self):
        with tempfile.TemporaryDirectory() as directory:
            missing = Path(directory) / "missing.json"
            folder = Path(directory) / "folder"
            folder.mkdir()
            result = search_recovery_locations(
                self.version.record_id, backup_paths=[missing, folder]
            )
            self.assertEqual(result["status"], "locations_not_fully_accessible")
            self.assertEqual(len(result["inaccessible_locations"]), 1)
            self.assertEqual(result["searched_locations"][0]["status"], "not_present_at_supplied_path")

    def test_reports_invalid_backup_without_using_it_as_evidence(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "invalid.json"
            path.write_text('{"not":"a backup"}', encoding="utf-8")
            result = search_recovery_locations(
                self.version.record_id, backup_paths=[path]
            )
            self.assertEqual(result["status"], "evidence_unreadable_or_invalid")
            self.assertEqual(result["validated_backup_matches"], [])

    def test_checkpoint_reference_can_be_correlated_with_backup(self):
        checkpoint = create_checkpoint_record(self.backup, 1)
        with tempfile.TemporaryDirectory() as directory:
            backup_path = Path(directory) / "backup.json"
            checkpoint_path = Path(directory) / "checkpoint.json"
            save_recovery_backup(self.backup, backup_path)
            import json
            checkpoint_path.write_text(json.dumps(checkpoint), encoding="utf-8")
            result = search_recovery_locations(
                self.version.record_id,
                backup_paths=[backup_path],
                checkpoint_paths=[checkpoint_path],
            )
            self.assertEqual(result["validated_checkpoint_count"], 1)
            self.assertEqual(result["checkpoint_backup_references_matched"], 1)


if __name__ == "__main__":
    unittest.main()
