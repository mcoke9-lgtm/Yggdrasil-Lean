import json
import os
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

from yim001.audited import AuditedMemoryStore, load_audited, save_audited
from yim001.recovery import (
    create_recovery_backup, save_recovery_backup, restore_recovery_backup,
)


class IntakeTransferCliTests(unittest.TestCase):
    def test_batch_import_checkpoints_each_pending_packet(self):
        project = Path(__file__).resolve().parents[1]
        source = project / "src"
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            queue = root / "intake.json"
            store = root / "audited.json"
            content_file = root / "content.json"
            env = dict(os.environ, PYTHONPATH=str(source))

            def run(*args):
                return subprocess.run(
                    [sys.executable, "-m", "yim001.intake_cli", *map(str, args)],
                    cwd=project, env=env, capture_output=True, text=True,
                )

            self.assertEqual(run("init", queue, "phone-batch").returncode, 0)
            packet_ids = []
            for value in ("first", "second"):
                content_file.write_text(json.dumps({"text": value}), encoding="utf-8")
                added = run(
                    "add", queue, content_file,
                    "--source-kind", "chat_exchange", "--language", "English",
                )
                self.assertEqual(added.returncode, 0, added.stderr)
                packet_ids.append(json.loads(added.stdout)["packet_id"])

            audited = AuditedMemoryStore("phone")
            audited.add_tree_location("roots.archive", "roots", "Living Archive")
            audited.add_tree_location(
                "branches.projects", "branches", "Projects",
                parent_id="roots.archive",
            )
            save_audited(audited, store)

            imported = run(
                "import-pending", queue, store,
                "--primary-location", "roots.archive",
                "--context-location", "branches.projects",
            )
            self.assertEqual(imported.returncode, 0, imported.stderr)
            result = json.loads(imported.stdout)
            self.assertEqual(result["status"], "imported_pending_packets")
            self.assertEqual(result["imported_count"], 2)
            self.assertEqual({item["packet_id"] for item in result["packets"]}, set(packet_ids))

            final_store = load_audited(store)
            self.assertEqual(len(final_store.events), 2)
            for item in result["packets"]:
                self.assertEqual(len(final_store.versions(item["record_id"])), 1)
                self.assertIn(item["record_id"], final_store.records_at_tree_location("roots.archive"))
                self.assertIn(item["record_id"], final_store.records_at_tree_location("branches.projects"))
                self.assertEqual(
                    [(p.node_id, p.role) for p in final_store.placements_for(item["record_id"])],
                    [("roots.archive", "primary"), ("branches.projects", "contextual")],
                )

            # Verify that a validated backup can restore the imported records,
            # Tree relationships, and source content into a fresh store file.
            backup_path = root / "audited-backup.json"
            restored_path = root / "restored-audited.json"
            backup = create_recovery_backup(
                final_store, "batch-import-test",
                created_at="2026-11-17T16:30:00Z",
            )
            save_recovery_backup(backup, backup_path)
            restore_recovery_backup(
                backup, restored_path, authorized=True,
            )
            restored = load_audited(restored_path)
            for item in result["packets"]:
                restored_version = restored.versions(item["record_id"])[0]
                self.assertEqual(restored_version.content, {"text": "first"} if item["packet_id"] == packet_ids[0] else {"text": "second"})
                self.assertIn(item["record_id"], restored.records_at_tree_location("roots.archive"))
                self.assertIn(item["record_id"], restored.records_at_tree_location("branches.projects"))
            self.assertEqual(restored.events[-1].event_type, "snapshot.recovered")

            repeated = run("import-pending", queue, store, "--primary-location", "roots.archive")
            self.assertEqual(repeated.returncode, 0, repeated.stderr)
            self.assertEqual(json.loads(repeated.stdout)["status"], "no_pending_packets")

    def test_cli_import_and_repeat_report_existing_record(self):
        project = Path(__file__).resolve().parents[1]
        source = project / "src"
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            queue = root / "intake.json"
            store = root / "audited.json"
            content_file = root / "content.json"
            env = dict(os.environ, PYTHONPATH=str(source))

            def run(*args):
                return subprocess.run(
                    [sys.executable, "-m", "yim001.intake_cli", *map(str, args)],
                    cwd=project, env=env, capture_output=True, text=True,
                )

            self.assertEqual(run("init", queue, "phone").returncode, 0)
            content_file.write_text(json.dumps({"text": "CLI source"}), encoding="utf-8")
            added = run(
                "add", queue, content_file,
                "--source-kind", "chat_exchange", "--language", "English",
                "--timestamp", "2026-11-17T16:00:00Z",
            )
            self.assertEqual(added.returncode, 0, added.stderr)
            packet_id = json.loads(added.stdout)["packet_id"]

            audited = AuditedMemoryStore("phone")
            audited.add_tree_location("roots.archive", "roots", "Living Archive")
            save_audited(audited, store)

            imported = run("import", queue, store, packet_id, "--primary-location", "roots.archive")
            self.assertEqual(imported.returncode, 0, imported.stderr)
            result = json.loads(imported.stdout)
            self.assertEqual(result["status"], "imported")

            repeated = run("import", queue, store, packet_id, "--primary-location", "roots.archive")
            self.assertEqual(repeated.returncode, 0, repeated.stderr)
            self.assertEqual(json.loads(repeated.stdout)["status"], "already_imported_verified")
            final_store = load_audited(store)
            self.assertEqual(len(final_store.versions(result["record_id"])), 1)
            self.assertEqual(len(final_store.events), 1)


if __name__ == "__main__":
    unittest.main()
