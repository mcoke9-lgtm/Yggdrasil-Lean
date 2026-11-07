"""Portable bundle tests; synthetic fixtures only."""
import io
import json
import unittest
import zipfile
from copy import deepcopy

from src.adaptive_filesystem.portable_bundle import build_bundle, verify_bundle


class PortableBundleTests(unittest.TestCase):
    def setUp(self):
        self.records = [
            {"record_id": "root-1", "role": "root", "provenance": "fixture-A"},
            {"record_id": "leaf-1", "role": "leaf", "provenance": "fixture-A"},
        ]
        self.relationships = [{
            "relationship_id": "rel-1", "source_id": "root-1",
            "target_id": "leaf-1", "relationship_type": "supports",
            "status": "unresolved", "provenance": "fixture-A",
        }]
        self.bundle = build_bundle(self.records, self.relationships)

    def rewrite_zip(self, bundle, mutate):
        source = zipfile.ZipFile(io.BytesIO(bundle))
        files = {n: source.read(n) for n in source.namelist()}
        mutate(files)
        out = io.BytesIO()
        with zipfile.ZipFile(out, "w") as target:
            for name, data in files.items():
                target.writestr(name, data)
        return out.getvalue()

    def test_valid_bundle_verifies_offline(self):
        report = verify_bundle(self.bundle)
        self.assertEqual(report["status"], "VALID")
        self.assertEqual(report["record_count"], 2)
        self.assertEqual(report["relationship_count"], 1)

    def test_build_is_deterministic(self):
        self.assertEqual(self.bundle, build_bundle(self.records, self.relationships))

    def test_build_does_not_mutate_source(self):
        before_records = deepcopy(self.records)
        before_relationships = deepcopy(self.relationships)
        build_bundle(self.records, self.relationships)
        self.assertEqual(self.records, before_records)
        self.assertEqual(self.relationships, before_relationships)

    def test_tampered_data_fails_hash_check(self):
        def mutate(files):
            files["records.jsonl"] += b'{"record_id":"evil"}\n'
        report = verify_bundle(self.rewrite_zip(self.bundle, mutate))
        self.assertEqual(report["status"], "INVALID")
        self.assertTrue(any("hash_mismatch" in x for x in report["reasons"]))

    def test_incomplete_member_set_is_rejected(self):
        def mutate(files):
            del files["relationships.jsonl"]
        report = verify_bundle(self.rewrite_zip(self.bundle, mutate))
        self.assertEqual(report["status"], "INVALID")

    def test_dangling_relationship_is_rejected_even_with_updated_hash(self):
        def mutate(files):
            rel = dict(self.relationships[0], target_id="missing")
            payload = (json.dumps(rel, sort_keys=True, separators=(",", ":")) + "\n").encode()
            files["relationships.jsonl"] = payload
            manifest = json.loads(files["manifest.json"])
            import hashlib
            manifest["files"]["relationships.jsonl"]["sha256"] = hashlib.sha256(payload).hexdigest()
            manifest["files"]["relationships.jsonl"]["count"] = 1
            files["manifest.json"] = json.dumps(manifest, sort_keys=True, separators=(",", ":")).encode()
        report = verify_bundle(self.rewrite_zip(self.bundle, mutate))
        self.assertEqual(report["status"], "INVALID")
        self.assertIn("relationship_endpoint_missing", report["reasons"])

    def test_duplicate_record_id_is_rejected_even_with_updated_hash(self):
        def mutate(files):
            payload = files["records.jsonl"] + files["records.jsonl"].splitlines(keepends=True)[0]
            files["records.jsonl"] = payload
            manifest = json.loads(files["manifest.json"])
            import hashlib
            manifest["files"]["records.jsonl"]["sha256"] = hashlib.sha256(payload).hexdigest()
            manifest["files"]["records.jsonl"]["count"] = 3
            files["manifest.json"] = json.dumps(manifest, sort_keys=True, separators=(",", ":")).encode()
        report = verify_bundle(self.rewrite_zip(self.bundle, mutate))
        self.assertEqual(report["status"], "INVALID")
        self.assertIn("duplicate_record_id", report["reasons"])

    def test_unreadable_zip_is_rejected(self):
        report = verify_bundle(b"not a zip archive")
        self.assertEqual(report["status"], "INVALID")


if __name__ == "__main__":
    unittest.main()
