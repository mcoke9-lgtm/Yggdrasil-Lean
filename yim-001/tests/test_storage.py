import tempfile
import unittest
from pathlib import Path

from yim001 import MemoryStore, load_store, save_store
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
        from unittest.mock import patch
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

    def test_memory_store_round_trip_preserves_versions_and_conflicts(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "memory.json"
            original = MemoryStore()
            first = original.create_record({"v": 1}, {"tag": "root"})
            left = original.revise(first.record_id, {"v": 2})
            right = original.revise(first.record_id, {"v": 3}, parent_version_ids=[first.version_id])
            original.annotate_conflict(left.version_id, right.version_id)

            save_store(original, path)
            restored = load_store(path)
            self.assertEqual(restored.to_snapshot(), original.to_snapshot())
            self.assertEqual(restored.get_version(first.version_id).content, {"v": 1})
            self.assertEqual(set(restored.tips(first.record_id)), {left.version_id, right.version_id})
            self.assertEqual(restored.conflicts(first.record_id), original.conflicts(first.record_id))

    def test_memory_store_rejects_invalid_parent_graph(self):
        store = MemoryStore()
        first = store.create_record("root")
        second = store.revise(first.record_id, "child")
        snapshot = store.to_snapshot()
        child = next(row for row in snapshot["versions"] if row["version_id"] == second.version_id)
        child["parents"] = [second.version_id]
        with self.assertRaises(ValueError):
            MemoryStore.from_snapshot(snapshot)

    def test_snapshot_rejects_boolean_format_version(self):
        store = MemoryStore()
        store.create_record("root")
        snapshot = store.to_snapshot()
        snapshot["format_version"] = True
        with self.assertRaises(ValueError):
            MemoryStore.from_snapshot(snapshot)

    def test_snapshot_rejects_parentless_non_initial_version(self):
        store = MemoryStore()
        first = store.create_record("root")
        second = store.revise(first.record_id, "child")
        snapshot = store.to_snapshot()
        child = next(row for row in snapshot["versions"] if row["version_id"] == second.version_id)
        child["parents"] = []
        with self.assertRaises(ValueError):
            MemoryStore.from_snapshot(snapshot)

    def test_snapshot_rejects_duplicate_parent_ids(self):
        store = MemoryStore()
        first = store.create_record("root")
        second = store.revise(first.record_id, "child")
        snapshot = store.to_snapshot()
        child = next(row for row in snapshot["versions"] if row["version_id"] == second.version_id)
        child["parents"] = [first.version_id, first.version_id]
        with self.assertRaises(ValueError):
            MemoryStore.from_snapshot(snapshot)

    def test_snapshot_rejects_duplicate_conflict_annotations(self):
        store = MemoryStore()
        first = store.create_record("root")
        second = store.revise(first.record_id, "child")
        store.annotate_conflict(first.version_id, second.version_id)
        snapshot = store.to_snapshot()
        snapshot["conflicts"].append(dict(snapshot["conflicts"][0]))
        with self.assertRaises(ValueError):
            MemoryStore.from_snapshot(snapshot)

    def test_snapshot_rejects_unhashable_conflict_identity(self):
        store = MemoryStore()
        first = store.create_record("root")
        second = store.revise(first.record_id, "child")
        snapshot = store.to_snapshot()
        snapshot["conflicts"].append({
            "source_version_id": [],
            "target_version_id": second.version_id,
        })
        with self.assertRaises(ValueError):
            MemoryStore.from_snapshot(snapshot)

    def test_snapshot_rejects_empty_conflict_identity(self):
        store = MemoryStore()
        first = store.create_record("root")
        second = store.revise(first.record_id, "child")
        snapshot = store.to_snapshot()
        snapshot["conflicts"].append({
            "source_version_id": "",
            "target_version_id": second.version_id,
        })
        with self.assertRaises(ValueError):
            MemoryStore.from_snapshot(snapshot)


if __name__ == "__main__":
    unittest.main()
