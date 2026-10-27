import unittest

from adaptive_filesystem_runtime.attempt_ledger import AttemptRecord, decide_append


def attempt(**overrides):
    values = dict(
        project_id="project-1", operation_id="op-stable",
        attempt_id="attempt-1", idempotency_key="idem-1", attempt_number=1,
        operation_fingerprint="input-v1", branch_id="branch-a",
        checkpoint_id="AF-PILOT-006", result_status="blocked",
        evidence_reference="ci://run/123",
    )
    values.update(overrides)
    return AttemptRecord(**values)


class AttemptLedgerContractTests(unittest.TestCase):
    def test_first_attempt_can_append(self):
        self.assertEqual(decide_append((), attempt()).outcome, "APPEND")

    def test_exact_replay_is_idempotent(self):
        first = attempt()
        self.assertEqual(decide_append((first,), first).outcome, "IDEMPOTENT_REPLAY")

    def test_reused_idempotency_key_with_changed_payload_is_conflict(self):
        first = attempt()
        changed = attempt(result_status="completed")
        self.assertEqual(
            decide_append((first,), changed).outcome, "REJECT_CONFLICT"
        )

    def test_retry_keeps_operation_identity_but_increments_attempt(self):
        first = attempt()
        retry = attempt(
            attempt_id="attempt-2", idempotency_key="idem-2", attempt_number=2,
            operation_fingerprint="input-v2", branch_id="branch-after-chat-change",
            checkpoint_id="AF-PILOT-007",
        )
        self.assertEqual(decide_append((first,), retry).outcome, "APPEND")

    def test_new_chat_or_branch_does_not_make_attempt_one_valid_again(self):
        first = attempt()
        duplicate_operation_attempt = attempt(
            attempt_id="attempt-new", idempotency_key="idem-new",
            branch_id="new-chat-branch-label", attempt_number=1,
        )
        self.assertEqual(
            decide_append((first,), duplicate_operation_attempt).outcome,
            "REJECT_CONFLICT",
        )

    def test_attempt_numbers_must_be_sequential(self):
        self.assertEqual(
            decide_append((attempt(),), attempt(
                attempt_id="attempt-3", idempotency_key="idem-3", attempt_number=3,
            )).outcome,
            "REJECT_CONFLICT",
        )

    def test_distinct_operation_can_start_at_attempt_one(self):
        self.assertEqual(
            decide_append((attempt(),), attempt(
                operation_id="op-another", attempt_id="other-1",
                idempotency_key="other-idem", attempt_number=1,
            )).outcome,
            "APPEND",
        )

    def test_blank_fingerprint_fails_closed(self):
        self.assertEqual(
            decide_append((), attempt(operation_fingerprint=" ")).outcome,
            "REJECT_CONFLICT",
        )

    def test_invalid_existing_record_fails_closed(self):
        self.assertEqual(
            decide_append((None,), attempt()).outcome, "REJECT_CONFLICT"
        )


if __name__ == "__main__":
    unittest.main()
