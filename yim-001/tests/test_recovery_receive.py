import json
import os
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

from yim001 import (
    AuditedMemoryStore,
    create_checkpoint_record,
    create_recovery_backup,
    save_recovery_backup,
)


class RecoveryReceiveTests(unittest.TestCase):
    def setUp(self):
        self.source = AuditedMemoryStore("receiver-test")
        self.source.create_record({"payload": "portable"}, timestamp="2026-11-10T12:00:00Z")
        self.backup = create_recovery_backup(
            self.source, "usb-source", created_at="2026-11-10T12:01:00Z"
        )
        self.checkpoint = create_checkpoint_record(
            self.backup, 1, recorded_at="2026-11-10T12:02:00Z"
        )

    def _run(self, root, *, expected_source="usb-source", latest=1):
        script = Path(__file__).resolve().parents[1] / "scripts" / "recovery_receive.py"
        checkpoints = root / "checkpoints.json"
        checkpoints.write_text(json.dumps([self.checkpoint]), encoding="utf-8")
        backup_path = root / "backup-1.json"
        save_recovery_backup(self.backup, backup_path)
        env = os.environ.copy()
        env["PYTHONPATH"] = str(Path(__file__).resolve().parents[1] / "src")
        return subprocess.run(
            [sys.executable, str(script), "--checkpoints", str(checkpoints),
             "--backup", str(backup_path), "--expected-source", expected_source,
             "--expected-latest-sequence", str(latest)],
            capture_output=True, text=True, env=env,
        ), checkpoints, backup_path

    def test_receiver_verifies_complete_transfer_without_mutation(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            result, checkpoints, backup = self._run(root)
            report = json.loads(result.stdout)
            self.assertEqual(result.returncode, 0)
            self.assertEqual(report["status"], "VERIFIED")
            self.assertEqual(report["receiver_check"], "read_only")
            self.assertFalse(report["files_modified"])
            self.assertEqual(report["backup_files_supplied"], 1)
            self.assertEqual(json.loads(checkpoints.read_text()), [self.checkpoint])
            self.assertTrue(backup.exists())

    def test_wrong_external_source_anchor_is_rejected(self):
        with tempfile.TemporaryDirectory() as directory:
            result, _, _ = self._run(Path(directory), expected_source="other-usb")
            self.assertEqual(result.returncode, 2)
            self.assertEqual(json.loads(result.stdout)["status"], "CONFLICT")

    def test_stale_external_sequence_anchor_is_rejected(self):
        with tempfile.TemporaryDirectory() as directory:
            result, _, _ = self._run(Path(directory), latest=2)
            self.assertEqual(result.returncode, 2)
            self.assertEqual(json.loads(result.stdout)["status"], "STALE")

    def test_corrupt_backup_is_rejected(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            result, _, backup = self._run(root)
            backup.write_text('{"payload": {}, "sha256": "bad"}', encoding="utf-8")
            script = Path(__file__).resolve().parents[1] / "scripts" / "recovery_receive.py"
            env = os.environ.copy()
            env["PYTHONPATH"] = str(Path(__file__).resolve().parents[1] / "src")
            rerun = subprocess.run(
                [sys.executable, str(script), "--checkpoints", str(root / "checkpoints.json"),
                 "--backup", str(backup), "--expected-source", "usb-source",
                 "--expected-latest-sequence", "1"],
                capture_output=True, text=True, env=env,
            )
            self.assertEqual(rerun.returncode, 2)
            self.assertEqual(json.loads(rerun.stdout)["status"], "CONFLICT")


if __name__ == "__main__":
    unittest.main()
