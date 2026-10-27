/-!
PropagationHistory.lean
Status: in-memory association model with derived content-field changes and a separate
identity/branch review signal. Durable storage and execution remain out of scope.

Content-field propagation and identity/branch review are separate: changing a project
or branch identity must not be silently treated as an ordinary dependency-field update.
-/ 

import YggdrasilLean.MultiStepHistory
import YggdrasilLean.SelectivePropagation

namespace YggdrasilLean

/-- One transition together with the propagation review associated with it. -/
structure PropagationHistoryEntry where
  transition : TransitionRecord
  assessment : PropagationAssessment
  deriving DecidableEq, Repr

/-- Associate an assessment with the transition that prompted review. -/
def recordPropagationWithTransition
    (transition : TransitionRecord)
    (assessment : PropagationAssessment) : PropagationHistoryEntry :=
  { transition := transition, assessment := assessment }

/-- The combined entry retains the exact transition record. -/
theorem propagationEntry_retains_transition
    (transition : TransitionRecord) (assessment : PropagationAssessment) :
    (recordPropagationWithTransition transition assessment).transition = transition := by
  rfl

/-- The combined entry retains the exact propagation assessment. -/
theorem propagationEntry_retains_assessment
    (transition : TransitionRecord) (assessment : PropagationAssessment) :
    (recordPropagationWithTransition transition assessment).assessment = assessment := by
  rfl

/-- The prior state remains recoverable through the attached transition. -/
theorem propagationEntry_recovers_prior
    (transition : TransitionRecord) (assessment : PropagationAssessment) :
    recoverPriorState (recordPropagationWithTransition transition assessment).transition =
      transition.before := by
  rfl

/-- The resulting state remains available through the attached transition. -/
theorem propagationEntry_retains_result
    (transition : TransitionRecord) (assessment : PropagationAssessment) :
    (recordPropagationWithTransition transition assessment).transition.after =
      transition.after := by
  rfl


/-- Derive the propagation-relevant field set by comparing transition endpoints. -/
def deriveChangeSet (before after : ProjectState) : ChangeSet :=
  ⟨(if before.agreements != after.agreements then [.agreements] else []) ++
   (if before.evidence != after.evidence then [.evidence] else []) ++
   (if before.dependencies != after.dependencies then [.dependencies] else []) ++
   (if before.unresolved != after.unresolved then [.unresolved] else []) ++
   (if before.stateVersion != after.stateVersion then [.stateVersion] else [])⟩

/-- Build an assessment from the transition itself, rather than accepting a free change set. -/
def assessTransitionForPropagation
    (transition : TransitionRecord) (routes : List DependencyRoute) :
    PropagationHistoryEntry :=
  recordPropagationWithTransition transition
    (recordPropagationAssessment
      (deriveChangeSet transition.before transition.after) routes)

/-- The attached assessment's change set is computed from the recorded endpoints. -/
theorem transitionAssessment_usesDerivedChangeSet
    (transition : TransitionRecord) (routes : List DependencyRoute) :
    (assessTransitionForPropagation transition routes).assessment.change =
      deriveChangeSet transition.before transition.after := by
  rfl

/-- If the tracked state fields are identical, the derived change set is empty. -/
theorem identicalTrackedFields_deriveEmptyChangeSet
    (state : ProjectState) :
    deriveChangeSet state state = ⟨[]⟩ := by
  simp [deriveChangeSet]

/-- Identity changes are reviewed separately from content-field propagation. -/
structure IdentityBranchReview where
  projectIdentityChanged : Bool
  branchIdentityChanged : Bool
  deriving DecidableEq, Repr
/-- One review record unifies content routing and identity/branch review for a transition. -/
structure TransitionReviewEntry where
  transition : TransitionRecord
  propagation : PropagationAssessment
  identityBranch : IdentityBranchReview
  deriving DecidableEq, Repr


/-- Compare project and branch identities across a recorded transition. -/
def assessIdentityBranchTransition (before after : ProjectState) :
    IdentityBranchReview where
  projectIdentityChanged := before.projectId != after.projectId
  branchIdentityChanged := before.branchId != after.branchId

/-- Either identity change requires explicit identity/branch review. -/
def identityBranchReviewRequired (review : IdentityBranchReview) : Bool :=
  review.projectIdentityChanged || review.branchIdentityChanged

/-- A project identity change cannot pass without identity/branch review. -/
theorem projectIdentityChange_requiresIdentityReview
    (review : IdentityBranchReview)
    (h : review.projectIdentityChanged = true) :
    identityBranchReviewRequired review = true := by
  simp [identityBranchReviewRequired, h]

/-- A branch identity change cannot pass without identity/branch review. -/
theorem branchIdentityChange_requiresIdentityReview
    (review : IdentityBranchReview)
    (h : review.branchIdentityChanged = true) :
    identityBranchReviewRequired review = true := by
  simp [identityBranchReviewRequired, h]

/-- An ordinary transition with unchanged identities does not trigger identity review. -/
theorem unchangedIdentities_noIdentityReview
    (before after : ProjectState)
    (hProject : before.projectId = after.projectId)
    (hBranch : before.branchId = after.branchId) :
    identityBranchReviewRequired (assessIdentityBranchTransition before after) = false := by
  simp [identityBranchReviewRequired, assessIdentityBranchTransition, hProject, hBranch]

/-- Identity assessment is computed from the same recorded transition endpoints. -/
def assessTransitionIdentityBranch (transition : TransitionRecord) : IdentityBranchReview :=
  assessIdentityBranchTransition transition.before transition.after

/-- Build both review dimensions from the same immutable transition endpoints. -/
def assessCompleteTransition
    (transition : TransitionRecord) (routes : List DependencyRoute) : TransitionReviewEntry :=
  ⟨transition,
    recordPropagationAssessment
      (deriveChangeSet transition.before transition.after) routes,
    assessTransitionIdentityBranch transition⟩

/-- The unified review retains the exact transition that was assessed. -/
theorem completeTransitionReview_retainsTransition
    (transition : TransitionRecord) (routes : List DependencyRoute) :
    (assessCompleteTransition transition routes).transition = transition := by
  rfl

/-- Content propagation is derived from the unified review's transition endpoints. -/
theorem completeTransitionReview_usesDerivedChangeSet
    (transition : TransitionRecord) (routes : List DependencyRoute) :
    (assessCompleteTransition transition routes).propagation.change =
      deriveChangeSet transition.before transition.after := by
  rfl

/-- Identity review is derived from the same unified review's transition endpoints. -/
theorem completeTransitionReview_usesTransitionIdentity
    (transition : TransitionRecord) (routes : List DependencyRoute) :
    (assessCompleteTransition transition routes).identityBranch =
      assessIdentityBranchTransition transition.before transition.after := by
  rfl

/-- Explicit forks are distinguishable as branch-identity changes. -/
theorem forkProject_triggersBranchReview
    (state : ProjectState) (newBranchId : String)
    (h : state.branchId ≠ newBranchId) :
    (assessIdentityBranchTransition state (forkProject state newBranchId)).branchIdentityChanged = true := by
  simp [assessIdentityBranchTransition, forkProject, h]

/-! Concrete compile-time scenarios for identity and content changes. -/

/-- A fixed baseline used by the identity-review scenarios. -/
def identityReviewExampleState : ProjectState :=
  ⟨"project-A", "main", 0, [], [], [], []⟩

/-- Changing the project identity triggers identity/branch review. -/
example :
    identityBranchReviewRequired
      (assessIdentityBranchTransition identityReviewExampleState
        { identityReviewExampleState with projectId := "project-B" }) = true := by
  decide

/-- Explicitly forking to a distinct branch ID triggers identity/branch review. -/
example :
    identityBranchReviewRequired
      (assessIdentityBranchTransition identityReviewExampleState
        (forkProject identityReviewExampleState "experiment")) = true := by
  decide

/-- A content-only agreement update leaves both identities unchanged and needs no identity review. -/
example :
    identityBranchReviewRequired
      (assessIdentityBranchTransition identityReviewExampleState
        (recordAgreement identityReviewExampleState "new agreement")) = false := by
  decide

/-- A content-only update produces an empty identity-review signal in the unified record. -/
example :
    identityBranchReviewRequired
      (assessCompleteTransition
        ⟨identityReviewExampleState,
          recordAgreement identityReviewExampleState "new agreement"⟩
        exampleRoutes).identityBranch = false := by
  decide

/-- A project-ID change is visible in the unified transition review. -/
example :
    (assessCompleteTransition
      ⟨identityReviewExampleState,
        { identityReviewExampleState with projectId := "project-B" }⟩
      exampleRoutes).identityBranch.projectIdentityChanged = true := by
  decide

/-- A project-ID change is flagged for identity review in the unified record. -/
example :
    identityBranchReviewRequired
      (assessCompleteTransition
        ⟨identityReviewExampleState,
          { identityReviewExampleState with projectId := "project-B" }⟩
        exampleRoutes).identityBranch = true := by
  decide

/-- An explicit fork is retained alongside its identity-review signal in one record. -/
example :
    (assessCompleteTransition
      ⟨identityReviewExampleState,
        forkProject identityReviewExampleState "experiment"⟩
      exampleRoutes).identityBranch.branchIdentityChanged = true := by
  decide

end YggdrasilLean
