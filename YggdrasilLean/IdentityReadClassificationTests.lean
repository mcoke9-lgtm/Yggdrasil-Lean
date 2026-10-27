/-!
IdentityReadClassificationTests.lean

Status: pure Lean regression theorems for the evidence-level classifier.
These do not test a concrete adapter or establish backend completeness.
-/

import YggdrasilLean.ReviewHistoryAdapterTypes

namespace YggdrasilLean

private def evidence (coverage : LookupCompleteness)
    (termination : LookupTermination) (ids : List Nat) :
    IdentityLookupEvidence Nat :=
  { declaredScope := "test-scope"
    completeness := coverage
    termination := termination
    observedCandidateEventIds := ids }

/-- Repeated observations of one ID are not competing identities. -/
theorem repeated_id_is_not_ambiguous :
    hasAtLeastTwoDistinctIds ([7, 7, 7] : List Nat) = false := by
  decide

/-- Two distinct IDs are ambiguous regardless of their list order. -/
theorem distinct_ids_forward :
    hasAtLeastTwoDistinctIds ([7, 9] : List Nat) = true := by
  decide

theorem distinct_ids_reversed :
    hasAtLeastTwoDistinctIds ([9, 7] : List Nat) = true := by
  decide

/-- A pair of distinct IDs stays ambiguous when lookup continuation failed. -/
theorem ambiguity_precedes_failure :
    classifyIdentityRead (evidence .unknown .failedAfterObservations [7, 9]) =
      .ambiguous (evidence .unknown .failedAfterObservations [7, 9]) := by
  apply distinct_ids_override_failed_termination
  decide

/-- Failure without competing identities remains unavailable with observations. -/
theorem failure_without_ambiguity_is_unavailable :
    classifyIdentityRead (evidence .complete .failedAfterObservations [7, 7]) =
      .unavailableAfterObservations
        (evidence .complete .failedAfterObservations [7, 7]) := by
  simp [classifyIdentityRead, evidence, hasAtLeastTwoDistinctIds]

/-- Early stopping cannot yield a complete candidate set. -/
theorem stopped_early_is_incomplete :
    classifyIdentityRead (evidence .complete .stoppedEarly [7]) =
      .incomplete (evidence .complete .stoppedEarly [7]) := by
  simp [classifyIdentityRead, evidence, hasAtLeastTwoDistinctIds]

/-- Completed lookup with explicitly incomplete coverage remains incomplete. -/
theorem incomplete_coverage_stays_incomplete :
    classifyIdentityRead (evidence .incomplete .completed [7]) =
      .incomplete (evidence .incomplete .completed [7]) := by
  simp [classifyIdentityRead, evidence, hasAtLeastTwoDistinctIds]

/-- Completed lookup with unknown coverage remains unresolved. -/
theorem unknown_coverage_stays_unresolved_test :
    classifyIdentityRead (evidence .unknown .completed [7]) =
      .unresolved (evidence .unknown .completed [7]) := by
  simp [classifyIdentityRead, evidence, hasAtLeastTwoDistinctIds]

/-- Only a completed lookup marked complete reaches candidate-set classification. -/
theorem completed_complete_reaches_candidate_set :
    classifyIdentityRead (evidence .complete .completed [7]) =
      .completeCandidates (evidence .complete .completed [7]) := by
  simp [classifyIdentityRead, evidence, hasAtLeastTwoDistinctIds]


/-- Full classification is ambiguous for the same competing IDs in either order. -/
theorem classification_order_forward :
    classifyIdentityRead (evidence .unknown .completed [7, 9]) =
      .ambiguous (evidence .unknown .completed [7, 9]) := by
  apply distinct_ids_override_failed_termination
  decide

theorem classification_order_reversed :
    classifyIdentityRead (evidence .unknown .completed [9, 7]) =
      .ambiguous (evidence .unknown .completed [9, 7]) := by
  apply distinct_ids_override_failed_termination
  decide

/-- Empty observations can be complete candidates, but not public not-found. -/
theorem empty_complete_lookup_is_candidate_set :
    classifyIdentityRead (evidence .complete .completed []) =
      .completeCandidates (evidence .complete .completed []) := by
  simp [classifyIdentityRead, evidence, hasAtLeastTwoDistinctIds]

/-- Repeated copies of one ID still produce a single-candidate classification. -/
theorem repeated_id_complete_lookup_is_candidate_set :
    classifyIdentityRead (evidence .complete .completed [7, 7, 7]) =
      .completeCandidates (evidence .complete .completed [7, 7, 7]) := by
  simp [classifyIdentityRead, evidence, hasAtLeastTwoDistinctIds]

end YggdrasilLean
