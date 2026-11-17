import json
import tempfile
import unittest
from pathlib import Path

from yim001 import AuditedMemoryStore, create_recovery_backup, save_recovery_backup
from yim001.recovery_registry import (
    RecoveryRegistryError, create_recovery_registry, load_recovery_registry,
    register_recovery_location, search_recovery_registry,
)


class RecoveryRegistryTests(unittest.TestCase):
    def test_registry_persists_location_dependencies_and_searches_known_paths(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            registry_path = root / "locations.json"
            create_recovery_registry(registry_path, "living-tree-local")
            first = register_recovery_location(
                registry_path, root / "not-yet-available.json", "backup",
                source_instance_id="phone-A", logical_location="roots.memory",
                note="Primary local backup reference",
                registered_at="2026-11-17T12:00:00Z",
            )
            second = register_recovery_location(
                registry_path, root / "checkpoint.json", "checkpoint",
                source_instance_id="phone-A", logical_location="roots.memory",
                depends_on=[first["payload"]["entry_id"]],
                registered_at="2026-11-17T12:01:00Z",
            )
            loaded = load_recovery_registry(registry_path)
            self.assertEqual(len(loaded["entries"]), 2)
            self.assertEqual(second["previous_digest"], first["digest"])
            result = search_recovery_registry("record-unknown", registry_path)
            self.assertEqual(result["registry"]["hash_chain_entries_validated"], 2)
            self.assertEqual(result["expected_logical_locations"], ["roots.memory"])
            self.assertEqual(result["status"], "no_supplied_locations_available")
            self.assertEqual(result["registered_dependencies"][0]["depends_on"], ["location-000001"])
            dependency = result["dependency_report"][1]["dependencies"][0]
            self.assertEqual(dependency["entry_id"], "location-000001")
            self.assertEqual(dependency["search_status"], "not_present_at_supplied_path")
            self.assertTrue(dependency["dependency_is_metadata_not_custody_proof"])


    def test_registry_search_connects_lineage_members_to_registered_evidence(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            registry_path = root / "locations.json"
            backup_path = root / "phone-backup.json"
            create_recovery_registry(registry_path, "lineage-registry")
            store = AuditedMemoryStore("phone-A")
            original = store.create_record({"text": "source"})
            derived = store.transform_record(
                original.record_id, original.version_id, "translation",
                {"text": "translated"}, description="Translated source text",
            )
            save_recovery_backup(create_recovery_backup(store, "phone-A"), backup_path)
            registered = register_recovery_location(
                registry_path, backup_path, "backup",
                source_instance_id="phone-A",
                logical_location="roots.memory",
            )
            result = search_recovery_registry(derived.record_id, registry_path)
            lineage = result["lineage"]
            self.assertEqual(set(lineage["members"]), {original.record_id, derived.record_id})
            self.assertEqual(
                {item["record_id"] for item in lineage["member_evidence"]},
                {original.record_id, derived.record_id},
            )
            for evidence in lineage["member_evidence"]:
                self.assertEqual(
                    evidence["registry_location"]["entry_id"],
                    registered["payload"]["entry_id"],
                )
                self.assertEqual(
                    evidence["registry_location"]["logical_location"],
                    "roots.memory",
                )
            self.assertEqual(
                lineage["relationships"][0]["evidence"][0]["registry_location"]["entry_id"],
                registered["payload"]["entry_id"],
            )

    def test_registry_traces_lineage_across_dependent_backup_locations(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            registry_path = root / "locations.json"
            original_backup = root / "original.json"
            derived_backup = root / "derived.json"
            create_recovery_registry(registry_path, "distributed-lineage")
            store = AuditedMemoryStore("phone-A")
            original = store.create_record({"text": "source"})
            save_recovery_backup(
                create_recovery_backup(store, "phone-A"), original_backup
            )
            first = register_recovery_location(
                registry_path, original_backup, "backup",
                source_instance_id="phone-A",
                logical_location="roots.memory",
            )
            derived = store.transform_record(
                original.record_id, original.version_id, "translation",
                {"text": "translated"}, description="Translated source text",
            )
            save_recovery_backup(
                create_recovery_backup(store, "phone-A"), derived_backup
            )
            second = register_recovery_location(
                registry_path, derived_backup, "backup",
                source_instance_id="phone-A",
                logical_location="branches.language",
                depends_on=[first["payload"]["entry_id"]],
            )

            result = search_recovery_registry(derived.record_id, registry_path)
            lineage = result["lineage"]
            self.assertEqual(
                set(lineage["members"]), {original.record_id, derived.record_id}
            )
            evidence_by_record = {}
            for evidence in lineage["member_evidence"]:
                evidence_by_record.setdefault(evidence["record_id"], set()).add(
                    Path(evidence["path"]).name
                )
            self.assertIn("original.json", evidence_by_record[original.record_id])
            self.assertIn("derived.json", evidence_by_record[derived.record_id])
            self.assertEqual(
                result["dependency_report"][1]["dependencies"][0]["search_status"],
                "read_and_validated",
            )
            self.assertEqual(
                result["dependency_report"][1]["dependencies"][0]["entry_id"],
                first["payload"]["entry_id"],
            )
            self.assertEqual(second["payload"]["depends_on"], ["location-000001"])
            third = register_recovery_location(
                registry_path, derived_backup, "backup",
                source_instance_id="phone-A",
                logical_location="branches.language.translation",
                depends_on=[second["payload"]["entry_id"]],
            )
            extended = search_recovery_registry(derived.record_id, registry_path)
            self.assertEqual(
                extended["dependency_report"][2]["transitive_dependency_entry_ids"],
                ["location-000002", "location-000001"],
            )
            self.assertEqual(third["payload"]["depends_on"], ["location-000002"])
            self.assertEqual(lineage["status"], "lineage_links_found")

    def test_registry_refuses_overwrite_and_unknown_dependencies(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "locations.json"
            create_recovery_registry(path, "registry-A")
            before = path.read_bytes()
            with self.assertRaises(FileExistsError):
                create_recovery_registry(path, "registry-B")
            self.assertEqual(path.read_bytes(), before)
            with self.assertRaises(RecoveryRegistryError):
                register_recovery_location(path, "backup.json", "backup",
                                           depends_on=["not-registered"])

    def test_registry_detects_changed_entry(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "locations.json"
            create_recovery_registry(path, "registry-A")
            register_recovery_location(path, "backup.json", "backup")
            data = json.loads(path.read_text(encoding="utf-8"))
            data["entries"][0]["payload"]["path"] = "/different/path"
            path.write_text(json.dumps(data), encoding="utf-8")
            with self.assertRaises(RecoveryRegistryError):
                load_recovery_registry(path)


if __name__ == "__main__":
    unittest.main()
