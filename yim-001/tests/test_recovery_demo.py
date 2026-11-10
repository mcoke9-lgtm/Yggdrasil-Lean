import tempfile
import unittest
from pathlib import Path

from recovery_demo import run_demo


class RecoveryDemoTests(unittest.TestCase):
    def test_two_copy_corruption_detection_and_restore(self):
        with tempfile.TemporaryDirectory() as directory:
            result = run_demo(Path(directory))
            self.assertEqual(result["status"], "DEMONSTRATION_PASSED")
            self.assertEqual(result["intact_copy_verification"]["status"], "VERIFIED")
            self.assertTrue(result["damaged_copy_rejected"])
            self.assertTrue(result["pre_restore_bytes_preserved"])
            self.assertTrue(result["restored_snapshot_matches"])


if __name__ == "__main__":
    unittest.main()
