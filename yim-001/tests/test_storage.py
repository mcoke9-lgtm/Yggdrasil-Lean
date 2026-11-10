import json
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

from yim001.storage import StorageError, read_json_strict, write_json_atomic


class SnapshotStorageTests(unittest.TestCase):
    def test_round_trip_strict_json(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "snapshot.json"
            value = {"items": [1, True, None, "text"], "nested": {"x": 2}}
            write_json_atomic(path, value)
            self.assertEqual(read_json_strict(path), value)

    def test_rejects_non_finite_before_write(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "snapshot.json"
            path.write_text('{"safe":true}', encoding="utf-8")
            with self.assertRaises(ValueError):
                write_json_atomic(path, {"bad": float("nan")})
            self.assertEqual(read_json_strict(path), {"safe": True})

    def test_rejects_nonstandard_constant_on_read(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "snapshot.json"
            path.write_text('{"bad":NaN}', encoding="utf-8")
            with self.assertRaises(StorageError):
                read_json_strict(path)

    def test_rejects_duplicate_object_keys(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "snapshot.json"
            path.write_text('{"x":1,"x":2}', encoding="utf-8")
            with self.assertRaises(StorageError):
                read_json_strict(path)

    def test_interrupted_replace_preserves_previous_snapshot(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "snapshot.json"
            path.write_text('{"generation":1}', encoding="utf-8")
            with patch("yim001.storage.os.replace", side_effect=OSError("simulated interruption")):
                with self.assertRaises(OSError):
                    write_json_atomic(path, {"generation": 2})
            self.assertEqual(read_json_strict(path), {"generation": 1})
            self.assertEqual(sorted(p.name for p in Path(directory).iterdir()), ["snapshot.json"])

    def test_successful_replace_replaces_complete_snapshot(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "snapshot.json"
            path.write_text('{"generation":1}', encoding="utf-8")
            write_json_atomic(path, {"generation": 2, "complete": True})
            self.assertEqual(read_json_strict(path), {"complete": True, "generation": 2})


if __name__ == "__main__":
    unittest.main()
