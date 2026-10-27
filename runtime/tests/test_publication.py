import unittest

from adaptive_filesystem_runtime.publication import (
    PublicationStatus,
    RefUpdateRejected,
    publish_once,
)


class FakeRefAdapter:
    def __init__(self, head):
        self.head = head
        self.intervening_head = None
        self.raise_after_publish = False
        self.fail_read = False
        self.calls = 0

    def read_head(self, branch):
        if self.fail_read:
            raise OSError("read unavailable")
        return self.head

    def compare_and_swap(self, branch, expected_head, desired_head):
        self.calls += 1
        if self.intervening_head is not None:
            self.head = self.intervening_head
            self.intervening_head = None
        if self.head != expected_head:
            raise RefUpdateRejected("expected head no longer current")
        self.head = desired_head
        if self.raise_after_publish:
            raise TimeoutError("response lost after remote update")


class ConditionalPublicationTests(unittest.TestCase):
    def test_stale_publisher_cannot_overwrite_intervening_update(self):
        adapter = FakeRefAdapter("base")
        adapter.intervening_head = "newer-external"
        result = publish_once(adapter, "test-branch", "base", "stale-candidate")

        self.assertEqual(result.status, PublicationStatus.STALE_HEAD)
        self.assertEqual(result.observed_head, "newer-external")
        self.assertEqual(adapter.head, "newer-external")
        self.assertEqual(adapter.calls, 1)

    def test_success_requires_readback_confirmation(self):
        adapter = FakeRefAdapter("base")
        result = publish_once(adapter, "test-branch", "base", "candidate")

        self.assertEqual(result.status, PublicationStatus.PUBLISHED)
        self.assertEqual(result.observed_head, "candidate")

    def test_ambiguous_response_reconciles_if_desired_head_is_present(self):
        adapter = FakeRefAdapter("base")
        adapter.raise_after_publish = True
        result = publish_once(adapter, "test-branch", "base", "candidate")

        self.assertEqual(result.status, PublicationStatus.PUBLISHED)
        self.assertEqual(result.observed_head, "candidate")
        self.assertEqual(adapter.calls, 1)

    def test_ambiguous_response_never_retries_when_head_is_unchanged(self):
        adapter = FakeRefAdapter("base")
        adapter.compare_and_swap = lambda *args: (_ for _ in ()).throw(
            TimeoutError("unknown remote outcome")
        )
        result = publish_once(adapter, "test-branch", "base", "candidate")

        self.assertEqual(result.status, PublicationStatus.UNKNOWN)
        self.assertEqual(result.observed_head, "base")

    def test_ambiguous_response_with_unrelated_new_head_is_stale_not_retryable(self):
        adapter = FakeRefAdapter("base")
        adapter.compare_and_swap = lambda *args: (
            setattr(adapter, "head", "other-writer"),
            (_ for _ in ()).throw(TimeoutError("unknown remote outcome")),
        )
        result = publish_once(adapter, "test-branch", "base", "candidate")

        self.assertEqual(result.status, PublicationStatus.STALE_HEAD)
        self.assertEqual(result.observed_head, "other-writer")

    def test_unreadable_head_after_ambiguous_response_is_unknown(self):
        adapter = FakeRefAdapter("base")
        adapter.compare_and_swap = lambda *args: (
            setattr(adapter, "fail_read", True),
            (_ for _ in ()).throw(TimeoutError("unknown remote outcome")),
        )
        result = publish_once(adapter, "test-branch", "base", "candidate")

        self.assertEqual(result.status, PublicationStatus.UNKNOWN)
        self.assertIsNone(result.observed_head)

    def test_rejects_empty_and_identical_heads(self):
        adapter = FakeRefAdapter("base")
        with self.assertRaises(ValueError):
            publish_once(adapter, "", "base", "candidate")
        with self.assertRaises(ValueError):
            publish_once(adapter, "branch", "base", "base")
        self.assertEqual(adapter.calls, 0)


if __name__ == "__main__":
    unittest.main()
