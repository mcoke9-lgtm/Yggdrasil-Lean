/-!
PropagationHistory.lean
Status: in-memory association model; causal field-diff derivation and durable storage remain out of scope.

A propagation assessment is paired with the transition it is intended to explain.
The pairing preserves both records for review. It does not yet prove that the
assessment's ChangeSet was derived from the transition's before/after states.
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

end YggdrasilLean
