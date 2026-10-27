import unittest

from adaptive_filesystem_runtime.alternative_work import Candidate, WorkRequest, evaluate_work


def candidate(candidate_id, **overrides):
    values = dict(
        candidate_id=candidate_id, description=f"work {candidate_id}",
        relevant=True, feasible=True, authorized=True, duplicate=False,
        integrity_ok=True, preserves_continuity=False, removes_dependency=False,
        concrete_progress=True, unresolved_dependencies=0, waiting_on_dependency=False,
    )
    values.update(overrides)
    return Candidate(**values)


def request(**overrides):
    values = dict(
        request_id="req-1", active_branch="branch-a", checkpoint_id="AF-PILOT-006",
        current_goal="verify recovery", blocked_operation="read live Airtable",
        observed_failure="credentials unavailable", blocked=True,
    )
    values.update(overrides)
    return WorkRequest(**values)


class AlternativeWorkTests(unittest.TestCase):
    def test_unblocked_operation_continues_original(self):
        result = evaluate_work(request(blocked=False))
        self.assertEqual(result.outcome, "CONTINUE_ORIGINAL")
        self.assertEqual(result.execution_status, "not_attempted")

    def test_rank_uses_continuity_then_dependency_removal_then_progress(self):
        result = evaluate_work(request(candidates=(
            candidate("low", concrete_progress=True),
            candidate("best", preserves_continuity=True, removes_dependency=True,
                      concrete_progress=True, unresolved_dependencies=2),
            candidate("middle", preserves_continuity=True, concrete_progress=True),
        )))
        self.assertEqual(result.outcome, "SELECT_ALTERNATIVE")
        self.assertEqual(result.recommended_candidate_ids, ("best",))

    def test_exact_tie_preserves_both_options(self):
        result = evaluate_work(request(candidates=(candidate("b"), candidate("a"))))
        self.assertEqual(result.recommended_candidate_ids, ("a", "b"))
        self.assertIn("equally ranked", result.next_step)

    def test_unknown_permission_fails_closed(self):
        result = evaluate_work(request(
            candidates=(candidate("maybe", authorized=None),),
            search_scope_exhausted=True,
        ))
        self.assertEqual(result.outcome, "STOP_NO_ALTERNATIVE")
        self.assertFalse(result.candidate_assessments[0].eligible)
        self.assertIn("authorization_unknown_or_false",
                      result.candidate_assessments[0].rejected_reasons)

    def test_integrity_gate_rejects_candidate_even_if_other_gates_pass(self):
        result = evaluate_work(request(
            candidates=(candidate("bad", integrity_ok=False),),
            search_scope_exhausted=True,
        ))
        self.assertEqual(result.outcome, "STOP_NO_ALTERNATIVE")
        self.assertFalse(result.candidate_assessments[0].eligible)

    def test_retry_requires_material_change_and_budget(self):
        good = evaluate_work(request(retry_requested=True, retry_material_change=True))
        bad = evaluate_work(request(retry_requested=True, retry_material_change=False,
                                    search_scope_exhausted=True))
        spent = evaluate_work(request(retry_requested=True, retry_material_change=True,
                                      prior_retry_count=1))
        self.assertEqual(good.outcome, "RETRY_JUSTIFIED")
        self.assertEqual(bad.outcome, "STOP_NO_ALTERNATIVE")
        self.assertEqual(spent.outcome, "STOP_NO_ALTERNATIVE")

    def test_wait_only_when_dependency_wait_is_productive(self):
        waiting = candidate("wait", feasible=False, waiting_on_dependency=True)
        no_wait = evaluate_work(request(candidates=(waiting,), dependency_wait_is_productive=False))
        yes_wait = evaluate_work(request(candidates=(waiting,), dependency_wait_is_productive=True))
        self.assertEqual(no_wait.outcome, "UNRESOLVED")
        self.assertEqual(yes_wait.outcome, "WAIT_FOR_DEPENDENCY")

    def test_stop_requires_exhausted_search_scope(self):
        candidate_unknown = candidate("unknown", relevant=None)
        not_done = evaluate_work(request(candidates=(candidate_unknown,), search_scope_exhausted=False))
        done = evaluate_work(request(candidates=(candidate_unknown,), search_scope_exhausted=True))
        self.assertEqual(not_done.outcome, "UNRESOLVED")
        self.assertEqual(done.outcome, "STOP_NO_ALTERNATIVE")

    def test_invalid_request_has_no_side_effects(self):
        result = evaluate_work(request(active_branch=" "))
        self.assertEqual(result.outcome, "INVALID_INPUT")
        self.assertEqual(result.execution_status, "not_attempted")
        self.assertEqual(result.persistence_status, "not_persisted")

    def test_duplicate_candidate_ids_are_rejected(self):
        result = evaluate_work(request(candidates=(candidate("x"), candidate("x"))))
        self.assertEqual(result.outcome, "INVALID_INPUT")

    def test_candidate_gates_are_all_required(self):
        candidate_value = candidate("no", relevant=False, feasible=False, duplicate=True)
        result = evaluate_work(request(candidates=(candidate_value,), search_scope_exhausted=True))
        self.assertFalse(result.candidate_assessments[0].eligible)
        self.assertEqual(len(result.candidate_assessments[0].rejected_reasons), 3)

    def test_decision_preserves_branch_and_checkpoint(self):
        result = evaluate_work(request(candidates=(candidate("alt"),)))
        self.assertEqual(result.active_branch, "branch-a")
        self.assertEqual(result.checkpoint_id, "AF-PILOT-006")
        self.assertEqual(result.persistence_status, "not_persisted")


if __name__ == "__main__":
    unittest.main()
