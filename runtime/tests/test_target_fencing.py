import unittest

from adaptive_filesystem_runtime.target_fencing import InMemoryFencedTarget


class TargetFencingTests(unittest.TestCase):
    def setUp(self):
        self.target = InMemoryFencedTarget()

    def test_stale_worker_rejected_after_newer_generation_reaches_target(self):
        first = self.target.apply("op-1", 1, "key-a", "write A")
        replacement = self.target.apply("op-1", 2, "key-b", "write B")
        stale = self.target.apply("op-1", 1, "key-c", "stale write")

        self.assertEqual(first.status, "APPLIED")
        self.assertEqual(replacement.status, "APPLIED")
        self.assertEqual(stale.status, "STALE_TOKEN")
        self.assertEqual(self.target.highest_token("op-1"), 2)
        self.assertEqual([effect[3] for effect in self.target.effects], ["write A", "write B"])

    def test_exact_request_replays_without_second_effect(self):
        self.assertEqual(self.target.apply("op-1", 1, "key-a", "write A").status, "APPLIED")
        self.assertEqual(self.target.apply("op-1", 1, "key-a", "write A").status, "IDEMPOTENT_REPLAY")
        self.assertEqual(len(self.target.effects), 1)

    def test_reused_idempotency_key_with_changed_payload_conflicts(self):
        self.target.apply("op-1", 1, "key-a", "write A")
        conflict = self.target.apply("op-1", 1, "key-a", "different write")
        self.assertEqual(conflict.status, "IDEMPOTENCY_CONFLICT")
        self.assertEqual(len(self.target.effects), 1)

    def test_stale_token_is_rejected_even_with_known_idempotency_key(self):
        self.target.apply("op-1", 2, "key-a", "write A")
        result = self.target.apply("op-1", 1, "key-a", "write A")
        self.assertEqual(result.status, "STALE_TOKEN")
        self.assertEqual(len(self.target.effects), 1)

    def test_token_is_scoped_per_operation(self):
        self.target.apply("op-1", 5, "key-a", "write A")
        result = self.target.apply("op-2", 1, "key-b", "write B")
        self.assertEqual(result.status, "APPLIED")

    def test_invalid_tokens_fail_closed(self):
        for token in (0, -1, True, 1.5):
            with self.subTest(token=token):
                self.assertEqual(self.target.apply("op-1", token, "key", "payload").status, "INVALID")


if __name__ == "__main__":
    unittest.main()
