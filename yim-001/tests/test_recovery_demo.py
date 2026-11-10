import json
import os
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path


class RecoveryDemoTests(unittest.TestCase):
    def test_two_copy_corruption_detection_and_restore(self):
        project = Path(__file__).resolve().parents[1]
        script = project / "scripts" / "recovery_demo.py"
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory) / "demo"
            env = os.environ.copy()
            env["PYTHONPATH"] = str(project / "src")
            completed = subprocess.run(
                [sys.executable, str(script), "--root", str(root)],
                check=True, capture_output=True, text=True, env=env,
            )
            result = json.loads(completed.stdout)
            self.assertEqual(result["status"], "DEMONSTRATION_PASSED")
            self.assertEqual(result["intact_copy_verification"]["status"], "VERIFIED")
            self.assertTrue(result["damaged_copy_rejected"])
            self.assertTrue(result["pre_restore_bytes_preserved"])
            self.assertTrue(result["restored_snapshot_matches"])


if __name__ == "__main__":
    unittest.main()
