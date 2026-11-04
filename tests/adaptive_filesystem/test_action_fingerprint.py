"""Tests for the constrained, versioned action-fingerprint profile."""
import unittest

from adaptive_filesystem.action_fingerprint import action_fingerprint, canonical_action_payload


class ActionFingerprintTests(unittest.TestCase):
    def setUp(self):
        self.valid = {
            "actor": "executor",
            "source_branch": "Roots",
            "source_checkpoint": "ckpt-7",
            "target_branch": "Trunk",
            "scope": ["advance", "write"],
        }

    def test_AF01_same_action_has_same_fingerprint(self):
        self.assertEqual(action_fingerprint(self.valid), action_fingerprint(dict(self.valid)))

    def test_AF02_scope_is_normalized_as_a_set(self):
        other = {**self.valid, "scope": ["write", "advance"]}
        self.assertEqual(action_fingerprint(self.valid), action_fingerprint(other))

    def test_AF03_duplicate_scope_is_rejected(self):
        invalid = {**self.valid, "scope": ["write", "write"]}
        with self.assertRaises(ValueError):
            action_fingerprint(invalid)

    def test_AF04_missing_identity_field_is_rejected(self):
        invalid = dict(self.valid)
        del invalid["source_checkpoint"]
        with self.assertRaises(ValueError):
            canonical_action_payload(invalid)

    def test_AF05_scope_must_be_a_list_of_nonempty_strings(self):
        for scope in ("write", [], ["write", ""], ["write", 3]):
            with self.subTest(scope=scope):
                with self.assertRaises(ValueError):
                    action_fingerprint({**self.valid, "scope": scope})

    def test_AF06_fingerprint_is_version_prefixed_sha256(self):
        result = action_fingerprint(self.valid)
        self.assertTrue(result.startswith("sha256:"))
        self.assertEqual(len(result), len("sha256:") + 64)


if __name__ == "__main__":
    unittest.main()
