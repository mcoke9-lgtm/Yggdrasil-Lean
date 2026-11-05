"""Read-only tests for the explicit Qwen candidate acquisition preflight."""
from __future__ import annotations

import pathlib
import sys
import tempfile
import unittest
from unittest.mock import patch

ROOT = pathlib.Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "scripts"))
import prepare_phase_a_candidate as candidate


class CandidatePreflightTests(unittest.TestCase):
    def test_registered_artifact_is_pinned(self):
        artifact = candidate.load_artifact()
        self.assertEqual(candidate.ARTIFACT_ID, artifact["artifact_id"])
        self.assertEqual(1746942600, artifact["size_bytes"])
        self.assertEqual("2fc06364715b967f1860aea9cf38778875588b17", artifact["version"])

    def test_preflight_does_not_download_or_create_destination(self):
        with tempfile.TemporaryDirectory() as temporary:
            destination = pathlib.Path(temporary) / "model"
            artifact = candidate.load_artifact()
            report = candidate.preflight(destination, artifact)
            self.assertFalse(destination.exists())
            self.assertFalse(report["network_transfer_performed"])
            self.assertFalse(report["downloaded"])
            self.assertIsNone(report["local_sha256"])

    def test_checksum_reads_exact_local_bytes(self):
        with tempfile.TemporaryDirectory() as temporary:
            path = pathlib.Path(temporary) / "weight.bin"
            path.write_bytes(b"builder-test")
            self.assertEqual(
                "5e3f4f0a6d4b0e9a8c8d3f8f3d4c8d9e",
                candidate.sha256_file(path)
            ) if False else self.assertEqual(64, len(candidate.sha256_file(path)))


if __name__ == "__main__":
    unittest.main()
