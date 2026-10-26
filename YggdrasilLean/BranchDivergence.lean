/-!
BranchDivergence.lean
Status: formalized branch comparison and explicit conflict signaling.
Scope: immutable state values and agreement-list divergence; no automatic merge,
semantic conflict resolution, or durable persistence is implemented here.

Principle: preserve both branch states and expose the difference. A merge policy
must be explicit; disagreement is data, not permission to overwrite.
-/

import YggdrasilLean.BranchForking

namespace YggdrasilLean

/-- The result of comparing two branch snapshots. -/
inductive BranchRelation where
  | sameState
  | leftExtendsRight
  | rightExtendsLeft
  | divergent
  deriving DecidableEq, Repr

/-- Classify states by their agreement histories, without mutating either input. -/
def compareAgreementStates (left right : ProjectState) : BranchRelation :=
  if left.agreements = right.agreements then
    .sameState
  else if right.agreements <+: left.agreements then
    .leftExtendsRight
  else if left.agreements <+: right.agreements then
    .rightExtendsLeft
  else
    .divergent

/-- Equal agreement histories are classified as the same state for this comparison. -/
theorem compareAgreementStates_equal
    (left right : ProjectState) (h : left.agreements = right.agreements) :
    compareAgreementStates left right = .sameState := by
  simp [compareAgreementStates, h]

/-- A proposed merge must state whether it needs conflict review. -/
inductive MergeAssessment where
  | readyToReview
  | conflictReviewRequired
  deriving DecidableEq, Repr

/-- Conservative assessment: divergent agreement histories require explicit review. -/
def assessAgreementMerge (left right : ProjectState) : MergeAssessment :=
  match compareAgreementStates left right with
  | .divergent => .conflictReviewRequired
  | _ => .readyToReview

/-- Divergent histories are never silently marked ready for merge. -/
theorem divergent_requires_review
    (left right : ProjectState)
    (h : compareAgreementStates left right = .divergent) :
    assessAgreementMerge left right = .conflictReviewRequired := by
  simp [assessAgreementMerge, h]

/-- A branch pair preserves both original values as a record of the comparison. -/
structure BranchPair where
  left : ProjectState
  right : ProjectState
  deriving DecidableEq, Repr

/-- Comparison produces a relation while retaining both snapshots. -/
def compareBranches (pair : BranchPair) : BranchRelation × BranchPair :=
  (compareAgreementStates pair.left pair.right, pair)

/-- Comparing branches retains the complete pair, including both branch identities. -/
theorem compareBranches_preserves_pair (pair : BranchPair) :
    (compareBranches pair).2 = pair := by
  rfl

end YggdrasilLean
