import unittest

from yim001.reconcile import CreationEvidence, Outcome, reconcile_creation_event


class ReconciliationTests(unittest.TestCase):
    def setUp(self):
        self.expected = dict(
            expected_event_id="event-1",
            expected_record_id="record-1",
            expected_creation_type="create",
            expected_record_digest="sha256:abc",
        )
        self.good = CreationEvidence("event-1", "record-1", "create", "sha256:abc", 0)

    def run_case(self, evidence, **kwargs):
        return reconcile_creation_event(evidence=evidence, **self.expected, **kwargs).outcome

    def test_consistent(self):
        self.assertEqual(self.run_case([self.good]), Outcome.CONSISTENT)

    def test_not_found(self):
        self.assertEqual(self.run_case([]), Outcome.NOT_FOUND)

    def test_incomplete_missing_history(self):
        self.assertEqual(self.run_case([self.good], history_state="missing"), Outcome.INCOMPLETE)

    def test_incomplete_missing_field(self):
        partial = CreationEvidence("event-1", "record-1", None, "sha256:abc", 0)
        self.assertEqual(self.run_case([partial]), Outcome.INCOMPLETE)

    def test_conflict(self):
        wrong = CreationEvidence("event-1", "record-1", "create", "sha256:wrong", 0)
        self.assertEqual(self.run_case([wrong]), Outcome.CONFLICT)

    def test_ambiguous_duplicate_event(self):
        duplicate = CreationEvidence("event-1", "record-1", "create", "sha256:abc", 1)
        self.assertEqual(self.run_case([self.good, duplicate]), Outcome.AMBIGUOUS)

    def test_uncertain_malformed_history(self):
        self.assertEqual(self.run_case([self.good], history_state="malformed"), Outcome.UNCERTAIN)

    def test_invalid_history_state_rejected(self):
        with self.assertRaises(ValueError):
            self.run_case([self.good], history_state="maybe")

    def test_negative_sequence_is_conflict(self):
        invalid = CreationEvidence("event-1", "record-1", "create", "sha256:abc", -1)
        self.assertEqual(self.run_case([invalid]), Outcome.CONFLICT)

    def test_boolean_sequence_is_conflict(self):
        invalid = CreationEvidence("event-1", "record-1", "create", "sha256:abc", True)
        self.assertEqual(self.run_case([invalid]), Outcome.CONFLICT)

    def test_float_sequence_is_conflict(self):
        invalid = CreationEvidence("event-1", "record-1", "create", "sha256:abc", 0.0)
        self.assertEqual(self.run_case([invalid]), Outcome.CONFLICT)

    def test_read_only_result_does_not_change_evidence(self):
        evidence = [self.good]
        before = tuple(evidence)
        reconcile_creation_event(evidence=evidence, **self.expected)
        self.assertEqual(tuple(evidence), before)


if __name__ == "__main__":
    unittest.main()
