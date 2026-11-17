import json
import os
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

from yim001.audited import AuditedMemoryStore, load_audited, save_audited


class IntakeTransferCliTests(unittest.TestCase):
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
            self.assertEqual(json.loads(repeated.stdout)["status"], "already_imported")
            final_store = load_audited(store)
            self.assertEqual(len(final_store.versions(result["record_id"])), 1)
            self.assertEqual(len(final_store.events), 1)


if __name__ == "__main__":
    unittest.main()
