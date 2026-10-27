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

/-- Content review is needed when at least one dependency route is selected. -/
def contentReviewRequired (review : PropagationAssessment) : Bool :=
  !review.change.changedFields.isEmpty ||
    review.decisions.any fun decision => decision.selectedForReview

/-- The unified review requires attention if identity review or content review is flagged. -/
def completeTransitionReviewRequired (review : TransitionReviewEntry) : Bool :=
  identityBranchReviewRequired review.identityBranch ||
    contentReviewRequired review.propagation

/-- A transition with no identity or routed-content differences requires no flagged review. -/
theorem unchangedTransition_noReviewRequired
    (state : ProjectState) (routes : List DependencyRoute) :
    completeTransitionReviewRequired
      (assessCompleteTransition ⟨state, state⟩ routes) = false := by
  simp [completeTransitionReviewRequired, identityBranchReviewRequired,
    contentReviewRequired, assessCompleteTransition, assessIdentityBranchTransition,
    assessIdentityBranchTransition, assessIdentityBranchTransition, deriveChangeSet,
    recordPropagationAssessment, changedWatchedFields, assessRoute, routeAffected]

/-- A tracked content change is flagged even if no declared route watches that field. -/
example :
    contentReviewRequired
      (assessCompleteTransition
        ⟨identityReviewExampleState,
          { identityReviewExampleState with dependencies := ["new-dependency"] }⟩
        exampleRoutes).propagation = true := by
  decide

/-- An identity-only change triggers the aggregate review flag even without content changes. -/
example :
    completeTransitionReviewRequired
      (assessCompleteTransition
        ⟨identityReviewExampleState,
          { identityReviewExampleState with projectId := "project-B" }⟩
        []) = true := by
  decide

/-!
Named rule snapshots make the review configuration itself auditable. The snapshot ID
is supplied by the caller; this model does not enforce global uniqueness or durable storage.
-/

/-- A named snapshot of the route configuration used for a review. -/
structure PropagationRuleSnapshot where
  ruleSetId : String
  routes : List DependencyRoute
  deriving DecidableEq, Repr

/-- A complete review record retains the transition, rule snapshot, and resulting review. -/
structure VersionedTransitionReviewEntry where
  transition : TransitionRecord
  ruleSnapshot : PropagationRuleSnapshot
  review : TransitionReviewEntry
  deriving DecidableEq, Repr

/-- Assess a transition under one explicit, named rule snapshot. -/
def assessWithRuleSnapshot
    (transition : TransitionRecord) (snapshot : PropagationRuleSnapshot) :
    VersionedTransitionReviewEntry :=
  ⟨transition, snapshot, assessCompleteTransition transition snapshot.routes⟩

/-- The versioned record retains the exact named rule snapshot. -/
theorem assessWithRuleSnapshot_retainsSnapshot
    (transition : TransitionRecord) (snapshot : PropagationRuleSnapshot) :
    (assessWithRuleSnapshot transition snapshot).ruleSnapshot = snapshot := by
  rfl

/-- The review is computed using the routes held in the retained snapshot. -/
theorem assessWithRuleSnapshot_usesSnapshotRoutes
    (transition : TransitionRecord) (snapshot : PropagationRuleSnapshot) :
    (assessWithRuleSnapshot transition snapshot).review =
      assessCompleteTransition transition snapshot.routes := by
  rfl

/-- Reassess the same transition under a new named snapshot, producing a separate value. -/
def reassessWithRuleSnapshot
    (historical : VersionedTransitionReviewEntry)
    (newSnapshot : PropagationRuleSnapshot) : VersionedTransitionReviewEntry :=
  assessWithRuleSnapshot historical.transition newSnapshot

/-- Reassessment preserves transition identity while recording the new snapshot explicitly. -/
theorem reassessWithRuleSnapshot_retainsTransition
    (historical : VersionedTransitionReviewEntry)
    (newSnapshot : PropagationRuleSnapshot) :
    (reassessWithRuleSnapshot historical newSnapshot).transition =
      historical.transition := by
  rfl

/-- Reassessment uses the new snapshot rather than silently inheriting the old one. -/
theorem reassessWithRuleSnapshot_usesNewSnapshot
    (historical : VersionedTransitionReviewEntry)
    (newSnapshot : PropagationRuleSnapshot) :
    (reassessWithRuleSnapshot historical newSnapshot).ruleSnapshot = newSnapshot := by
  rfl

/-- Example: a reassessment can change rules without rewriting the historical record. -/
example :
    let transition : TransitionRecord :=
      ⟨identityReviewExampleState,
        { identityReviewExampleState with evidence := ["source-1"] }⟩
    let oldSnapshot : PropagationRuleSnapshot :=
      ⟨"rules-v1", [⟨"evidence-route", [.evidence]⟩]⟩
    let historical := assessWithRuleSnapshot transition oldSnapshot
    let newSnapshot : PropagationRuleSnapshot :=
      ⟨"rules-v2", [⟨"agreement-route", [.agreements]⟩]⟩
    let revised := reassessWithRuleSnapshot historical newSnapshot
    historical.ruleSnapshot.ruleSetId = "rules-v1" ∧
      historical.ruleSnapshot.routes = [⟨"evidence-route", [.evidence]⟩] ∧
      historical.review.propagation.decisions.head!.selectedForReview = true ∧
      revised.ruleSnapshot.ruleSetId = "rules-v2" ∧
      revised.review.propagation.decisions.head!.selectedForReview = false := by
  decide

/-! The versioned records can themselves be accumulated without replacing history. -/

/-- In-memory append-only sequence of versioned transition reviews. -/
structure VersionedTransitionReviewHistory where
  entries : List VersionedTransitionReviewEntry
  deriving DecidableEq, Repr

/-- Append one versioned review as a new historical entry. -/
def appendVersionedReview
    (history : VersionedTransitionReviewHistory)
    (entry : VersionedTransitionReviewEntry) : VersionedTransitionReviewHistory :=
  ⟨history.entries ++ [entry]⟩

/-- Appending a versioned review preserves the exact prior prefix and appends one entry. -/
theorem appendVersionedReview_preservesOrder
    (history : VersionedTransitionReviewHistory)
    (entry : VersionedTransitionReviewEntry) :
    (appendVersionedReview history entry).entries = history.entries ++ [entry] := by
  rfl

/-- The appended versioned review is the final entry. -/
theorem appendVersionedReview_retainsLast
    (history : VersionedTransitionReviewHistory)
    (entry : VersionedTransitionReviewEntry) :
    (appendVersionedReview history entry).entries.getLast? = some entry := by
  simp [appendVersionedReview]

/-- Appending a reassessment preserves the original assessment as the first historical entry. -/
example :
    let transition : TransitionRecord :=
      ⟨identityReviewExampleState,
        { identityReviewExampleState with evidence := ["source-1"] }⟩
    let oldSnapshot : PropagationRuleSnapshot :=
      ⟨"rules-v1", [⟨"evidence-route", [.evidence]⟩]⟩
    let original := assessWithRuleSnapshot transition oldSnapshot
    let history : VersionedTransitionReviewHistory := ⟨[original]⟩
    let newSnapshot : PropagationRuleSnapshot :=
      ⟨"rules-v2", [⟨"agreement-route", [.agreements]⟩]⟩
    let reassessed := reassessWithRuleSnapshot original newSnapshot
    let revisedHistory := appendVersionedReview history reassessed
    revisedHistory.entries = [original, reassessed] ∧
      revisedHistory.entries.head! = original ∧
      revisedHistory.entries.getLast? = some reassessed ∧
      revisedHistory.entries.head!.ruleSnapshot.ruleSetId = "rules-v1" := by
  decide

/-- An in-memory review history retains transition reviews in append order. -/
structure TransitionReviewHistory where
  entries : List TransitionReviewEntry
  deriving DecidableEq, Repr

/-- Append one completed review without replacing earlier entries. -/
def appendTransitionReview
    (history : TransitionReviewHistory) (entry : TransitionReviewEntry) :
    TransitionReviewHistory :=
  ⟨history.entries ++ [entry]⟩

/-- Appending retains the prior history as the exact prefix of the new history. -/
theorem appendTransitionReview_preservesPriorEntries
    (history : TransitionReviewHistory) (entry : TransitionReviewEntry) :
    (appendTransitionReview history entry).entries = history.entries ++ [entry] := by
  rfl

/-- The appended review is the final entry in the resulting history. -/
theorem appendTransitionReview_retainsNewEntry
    (history : TransitionReviewHistory) (entry : TransitionReviewEntry) :
    (appendTransitionReview history entry).entries.getLast? = some entry := by
  simp [appendTransitionReview]

/-- Append two reviews in sequence while retaining the initial history. -/
def appendTwoTransitionReviews
    (history : TransitionReviewHistory)
    (first second : TransitionReviewEntry) : TransitionReviewHistory :=
  appendTransitionReview (appendTransitionReview history first) second

/-- Two sequential appends preserve the old prefix and the order of both new reviews. -/
theorem appendTwoTransitionReviews_preservesOrder
    (history : TransitionReviewHistory)
    (first second : TransitionReviewEntry) :
    (appendTwoTransitionReviews history first second).entries =
      history.entries ++ [first, second] := by
  simp [appendTwoTransitionReviews, appendTransitionReview, List.append_assoc]

/-- After two sequential appends, the second review is the final entry. -/
theorem appendTwoTransitionReviews_retainsLast
    (history : TransitionReviewHistory)
    (first second : TransitionReviewEntry) :
    (appendTwoTransitionReviews history first second).entries.getLast? =
      some second := by
  simp [appendTwoTransitionReviews, appendTransitionReview]

/-- Append a list of reviews while retaining the original history as a prefix. -/
def appendTransitionReviews
    (history : TransitionReviewHistory)
    (entries : List TransitionReviewEntry) : TransitionReviewHistory :=
  ⟨history.entries ++ entries⟩

/-- Batch append preserves all prior entries and the supplied entry order. -/
theorem appendTransitionReviews_preservesOrder
    (history : TransitionReviewHistory)
    (entries : List TransitionReviewEntry) :
    (appendTransitionReviews history entries).entries = history.entries ++ entries := by
  rfl

/-- Appending an empty batch leaves the history unchanged. -/
theorem appendTransitionReviews_empty_isIdentity
    (history : TransitionReviewHistory) :
    appendTransitionReviews history [] = history := by
  cases history
  rfl

/-- Batch append is associative with sequential appends. -/
theorem appendTransitionReviews_composes
    (history : TransitionReviewHistory)
    (firstBatch secondBatch : List TransitionReviewEntry) :
    appendTransitionReviews (appendTransitionReviews history firstBatch) secondBatch =
      appendTransitionReviews history (firstBatch ++ secondBatch) := by
  cases history
  simp [appendTransitionReviews, List.append_assoc]

/-- Reconstruct a transition review from its recorded transition and route configuration. -/
def reconstructTransitionReview
    (transition : TransitionRecord) (routes : List DependencyRoute) :
    TransitionReviewEntry :=
  assessCompleteTransition transition routes

/-- Reconstructed review retains the exact source transition. -/
theorem reconstructTransitionReview_retainsTransition
    (transition : TransitionRecord) (routes : List DependencyRoute) :
    (reconstructTransitionReview transition routes).transition = transition := by
  rfl

/-- Reconstructed content assessment is derived from the source transition endpoints. -/
theorem reconstructTransitionReview_usesDerivedChanges
    (transition : TransitionRecord) (routes : List DependencyRoute) :
    (reconstructTransitionReview transition routes).propagation.change =
      deriveChangeSet transition.before transition.after := by
  rfl

/-- Reconstructed identity review is derived from the same source transition. -/
theorem reconstructTransitionReview_usesIdentity
    (transition : TransitionRecord) (routes : List DependencyRoute) :
    (reconstructTransitionReview transition routes).identityBranch =
      assessIdentityBranchTransition transition.before transition.after := by
  rfl

/-- Reconstructing from the same transition and routes is deterministic. -/
theorem reconstructTransitionReview_deterministic
    (transition : TransitionRecord) (routes : List DependencyRoute) :
    reconstructTransitionReview transition routes =
      assessCompleteTransition transition routes := by
  rfl

/-- Rebuild a review from a stored history entry's transition. -/
def reconstructHistoryEntryReview
    (entry : PropagationHistoryEntry) (routes : List DependencyRoute) :
    TransitionReviewEntry :=
  reconstructTransitionReview entry.transition routes

/-- Reconstructed review retains the transition stored in the history entry. -/
theorem reconstructHistoryEntryReview_retainsTransition
    (entry : PropagationHistoryEntry) (routes : List DependencyRoute) :
    (reconstructHistoryEntryReview entry routes).transition = entry.transition := by
  rfl

/-- Reconstructing from an entry agrees with reconstructing from its transition directly. -/
theorem reconstructHistoryEntryReview_agrees
    (entry : PropagationHistoryEntry) (routes : List DependencyRoute) :
    reconstructHistoryEntryReview entry routes =
      reconstructTransitionReview entry.transition routes := by
  rfl

/-- Recompute a review using a supplied route configuration without mutating history. -/
def recomputeHistoryEntryReview
    (entry : PropagationHistoryEntry) (routes : List DependencyRoute) :
    TransitionReviewEntry :=
  reconstructTransitionReview entry.transition routes

/-- Recomputed review retains the transition from the history entry. -/
theorem recomputeHistoryEntryReview_retainsTransition
    (entry : PropagationHistoryEntry) (routes : List DependencyRoute) :
    (recomputeHistoryEntryReview entry routes).transition = entry.transition := by
  rfl

/-- Recomputed review uses the requested routes, not the stored assessment's decisions. -/
theorem recomputeHistoryEntryReview_usesRequestedRoutes
    (entry : PropagationHistoryEntry) (routes : List DependencyRoute) :
    (recomputeHistoryEntryReview entry routes).propagation =
      recordPropagationAssessment
        (deriveChangeSet entry.transition.before entry.transition.after) routes := by
  rfl

/-- A later route configuration may produce a different review decision. -/
example :
    let transition : TransitionRecord :=
      ⟨identityReviewExampleState,
        { identityReviewExampleState with evidence := ["source-1"] }⟩
    let originalAssessment :=
      recordPropagationAssessment (deriveChangeSet transition.before transition.after)
        [⟨"evidence-route", [.evidence]⟩]
    let entry : PropagationHistoryEntry :=
      ⟨transition, originalAssessment⟩
    let recomputed := recomputeHistoryEntryReview entry
      [⟨"agreement-route", [.agreements]⟩]
    entry.assessment.decisions.head!.selectedForReview = true ∧
      recomputed.propagation.decisions.head!.selectedForReview = false := by
  decide

/-- Recomputing with a new route list does not replace the historical assessment. -/
example :
    let transition : TransitionRecord :=
      ⟨identityReviewExampleState,
        { identityReviewExampleState with evidence := ["source-1"] }⟩
    let originalAssessment :=
      recordPropagationAssessment (deriveChangeSet transition.before transition.after)
        [⟨"evidence-route", [.evidence]⟩]
    let entry : PropagationHistoryEntry :=
      ⟨transition, originalAssessment⟩
    let _recomputed := recomputeHistoryEntryReview entry
      [⟨"agreement-route", [.agreements]⟩]
    entry.assessment = originalAssessment := by
  decide

/-- The original assessment stores the route configuration used at assessment time. -/
theorem historicalAssessment_retainsRoutes
    (change : ChangeSet) (routes : List DependencyRoute) :
    (recordPropagationAssessment change routes).routes = routes := by
  rfl

/-- The original assessment stores a decision for every route, in the same order. -/
theorem historicalAssessment_decisionOrder
    (change : ChangeSet) (routes : List DependencyRoute) :
    (recordPropagationAssessment change routes).decisions =
      routes.map (assessRoute change) := by
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

/-- One transition review can report an evidence change and project identity change together. -/
example :
    let after := { identityReviewExampleState with
      projectId := "project-B"
      evidence := ["source-1"] }
    let review := assessCompleteTransition
      ⟨identityReviewExampleState, after⟩ exampleRoutes
    review.propagation.change = ⟨[.evidence]⟩ ∧
      review.identityBranch.projectIdentityChanged = true ∧
      identityBranchReviewRequired review.identityBranch = true := by
  decide

/-- In a combined transition, the evidence consumer is selected for review. -/
example :
    let after := { identityReviewExampleState with
      projectId := "project-B"
      evidence := ["source-1"] }
    let review := assessCompleteTransition
      ⟨identityReviewExampleState, after⟩ exampleRoutes
    (review.propagation.decisions.head!).selectedForReview = true := by
  decide

end YggdrasilLean
