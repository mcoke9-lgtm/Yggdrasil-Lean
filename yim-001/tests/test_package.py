import hashlib
import json
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path
import zipfile

SCRIPT = Path(__file__).resolve().parents[1] / "scripts" / "package.py"


class PackageTests(unittest.TestCase):
    def test_package_is_verified_and_deterministic(self):
        with tempfile.TemporaryDirectory() as directory:
            first = Path(directory) / "first.zip"
            second = Path(directory) / "second.zip"
            subprocess.run([sys.executable, str(SCRIPT), "--output", str(first)], check=True, capture_output=True, text=True)
            subprocess.run([sys.executable, str(SCRIPT), "--output", str(second)], check=True, capture_output=True, text=True)
            self.assertEqual(hashlib.sha256(first.read_bytes()).digest(), hashlib.sha256(second.read_bytes()).digest())
            result = json.loads(subprocess.check_output([sys.executable, str(SCRIPT), "--verify", str(first)], text=True))
            self.assertGreater(result["files_verified"], 0)
            with zipfile.ZipFile(first) as archive:
                self.assertIn("yim-001/SHA256SUMS.json", archive.namelist())

    def test_package_verifier_detects_tampering(self):
        with tempfile.TemporaryDirectory() as directory:
            package = Path(directory) / "package.zip"
            subprocess.run([sys.executable, str(SCRIPT), "--output", str(package)], check=True, capture_output=True, text=True)
            with zipfile.ZipFile(package, "a") as archive:
                archive.writestr("yim-001/README.md", b"tampered")
            result = subprocess.run([sys.executable, str(SCRIPT), "--verify", str(package)], capture_output=True, text=True)
            self.assertNotEqual(result.returncode, 0)


if __name__ == "__main__":
    unittest.main()
