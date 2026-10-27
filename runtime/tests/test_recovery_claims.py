import tempfile
import unittest
from concurrent.futures import ThreadPoolExecutor
from threading import Barrier
from pathlib import Path

from adaptive_filesystem_runtime.attempt_ledger import AttemptRecord
from adaptive_filesystem_runtime.sqlite_ledger import SQLiteAttemptLedger


def record(**overrides):
    values = dict(project_id="project-1", operation_id="operation-1",
        attempt_id="attempt-1", idempotency_key="idem-1", attempt_number=1,
        operation_fingerprint="v1", branch_id="branch-a", checkpoint_id="checkpoint-a",
        result_status="started", evidence_reference=None)
    values.update(overrides)
    return AttemptRecord(**values)


class RecoveryClaimTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.path = Path(self.temp.name) / "ledger.sqlite3"
        self.ledger = SQLiteAttemptLedger(self.path)
        self.ledger.append(record())

    def tearDown(self):
        self.temp.cleanup()

    def claim(self, ledger=None, **overrides):
        args = dict(project_id="project-1", operation_id="operation-1",
            attempt_id="attempt-1", attempt_number=1, claim_id="claim-a",
            owner_id="worker-a", lease_seconds=10, now=100)
        args.update(overrides)
        return (ledger or self.ledger).claim_recovery_review(**args)

    def test_claim_acquired_and_persists_across_reopen(self):
        result = self.claim()
        self.assertEqual(result.status, "CLAIMED")
        reopened = SQLiteAttemptLedger(self.path)
        self.assertEqual(self.claim(ledger=reopened).status, "ALREADY_CLAIMED")

    def test_second_worker_cannot_claim_live_lease(self):
        self.assertEqual(self.claim().status, "CLAIMED")
        other = self.claim(ledger=SQLiteAttemptLedger(self.path),
            claim_id="claim-b", owner_id="worker-b", now=105)
        self.assertEqual(other.status, "CLAIMED_BY_OTHER")

    def test_expired_lease_can_be_reclaimed(self):
        self.assertEqual(self.claim().status, "CLAIMED")
        other = self.claim(claim_id="claim-b", owner_id="worker-b", now=111)
        self.assertEqual(other.status, "CLAIMED")
        self.assertEqual(other.lease_expires_at, 121)

    def test_stale_attempt_cannot_be_claimed(self):
        self.ledger.append(record(attempt_id="attempt-2", idempotency_key="idem-2",
            attempt_number=2, operation_fingerprint="v2"))
        self.assertEqual(self.claim().status, "STALE_ATTEMPT")

    def test_missing_operation_cannot_be_claimed(self):
        self.assertEqual(self.claim(operation_id="missing").status, "ATTEMPT_NOT_FOUND")

    def test_release_requires_exact_claim_owner(self):
        self.claim()
        self.assertFalse(self.ledger.release_recovery_review(
            "project-1", "operation-1", "claim-a", "worker-b"))
        self.assertTrue(self.ledger.release_recovery_review(
            "project-1", "operation-1", "claim-a", "worker-a"))
        self.assertFalse(self.ledger.release_recovery_review(
            "project-1", "operation-1", "claim-a", "worker-a"))

    def test_competing_workers_have_one_claim_winner(self):
        ledgers = (SQLiteAttemptLedger(self.path), SQLiteAttemptLedger(self.path))
        barrier = Barrier(2)
        def run(pair):
            ledger, name = pair
            barrier.wait()
            return self.claim(ledger=ledger, claim_id=f"claim-{name}",
                owner_id=f"worker-{name}", now=100).status
        with ThreadPoolExecutor(max_workers=2) as pool:
            outcomes = list(pool.map(run, zip(ledgers, ("a", "b"))))
        self.assertCountEqual(outcomes, ["CLAIMED", "CLAIMED_BY_OTHER"])


if __name__ == "__main__":
    unittest.main()
