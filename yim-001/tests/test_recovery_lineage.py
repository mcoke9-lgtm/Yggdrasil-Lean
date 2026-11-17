import tempfile
import unittest
from pathlib import Path

from yim001 import AuditedMemoryStore, create_recovery_backup, save_recovery_backup
from yim001.recovery_search import search_recovery_locations


class RecoveryLineageTests(unittest.TestCase):
    def test_search_traces_distinct_transformations_across_validated_backups(self):
        store = AuditedMemoryStore("lineage-test")
        original = store.create_record({"text": "original"})
        copied = store.transform_record(
            original.record_id, original.version_id, "copy", {"text": "copied"},
            description="Exact content copy into a distinct record",
        )
        translated = store.transform_record(
            copied.record_id, copied.version_id, "translation", {"text": "translated"},
            description="Translated wording",
        )
        reconstructed = store.transform_record(
            original.record_id, original.version_id, "reconstruction", {"text": "rebuilt"},
            description="Reconstructed from available evidence",
        )

        first_backup = create_recovery_backup(store, "device-A")
        store.revise(original.record_id, {"text": "revised original"})
        second_backup = create_recovery_backup(store, "device-A")

        with tempfile.TemporaryDirectory() as directory:
            first = Path(directory) / "first.json"
            second = Path(directory) / "second.json"
            save_recovery_backup(first_backup, first)
            save_recovery_backup(second_backup, second)
            result = search_recovery_locations(
                translated.record_id, backup_paths=[first, second]
            )

        lineage = result["lineage"]
        self.assertEqual(lineage["status"], "lineage_links_found")
        self.assertEqual(
            set(lineage["members"]),
            {original.record_id, copied.record_id, translated.record_id, reconstructed.record_id},
        )
        relations = {item["relationship"] for item in lineage["relationships"]}
        self.assertEqual(relations, {"copy", "translation", "reconstruction"})
        reconstruction = next(
            item for item in lineage["relationships"]
            if item["relationship"] == "reconstruction"
        )
        self.assertEqual(reconstruction["identity_relation"], "reconstruction_not_original")
        self.assertNotEqual(reconstructed.record_id, original.record_id)
        self.assertTrue(all(item["evidence"] for item in lineage["relationships"]))

    def test_no_lineage_claim_when_supplied_backups_have_no_transform_events(self):
        store = AuditedMemoryStore("lineage-empty")
        record = store.create_record("unrelated")
        backup = create_recovery_backup(store, "device-B")
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "backup.json"
            save_recovery_backup(backup, path)
            result = search_recovery_locations(record.record_id, backup_paths=[path])
        self.assertEqual(
            result["lineage"]["status"], "no_lineage_links_in_supplied_evidence"
        )
        self.assertEqual(result["lineage"]["members"], [record.record_id])
        self.assertIn("not proof", result["lineage"]["interpretation"])


if __name__ == "__main__":
    unittest.main()
