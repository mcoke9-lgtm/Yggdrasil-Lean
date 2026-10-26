/-!
HistoryReversibility.lean
Status: minimal one-step history/recovery kernel; broader persistence remains provisional.

A transition record stores the exact before and after states. Recovery here means
recovering the recorded prior state from that record, not reconstructing a whole
repository or recovering data that was never recorded.
-/

import YggdrasilLean.BranchContinuity

namespace YggdrasilLean

/-- A traceable record of one state transition. -/
structure TransitionRecord where
  before : ProjectState
  after : ProjectState
  description : String
  deriving DecidableEq, Repr

/-- Apply an agreement edit and retain both states in a transition record. -/
def recordAgreementWithHistory
    (project : ProjectState) (agreement : String) : TransitionRecord :=
  { before := project
    after := recordAgreement project agreement
    description := "record agreement: " ++ agreement }

/-- The transition record retains the exact prior state. -/
theorem transition_record_recovers_prior
    (project : ProjectState) (agreement : String) :
    (recordAgreementWithHistory project agreement).before = project := by
  rfl

/-- The transition record retains the exact resulting state. -/
theorem transition_record_retains_result
    (project : ProjectState) (agreement : String) :
    (recordAgreementWithHistory project agreement).after =
      recordAgreement project agreement := by
  rfl

/-- A recovery operation reads the prior state explicitly recorded in the event. -/
def recoverPriorState (event : TransitionRecord) : ProjectState :=
  event.before

/-- Recovering from a transition record returns the exact state before the edit. -/
theorem recoverPriorState_exact
    (project : ProjectState) (agreement : String) :
    recoverPriorState (recordAgreementWithHistory project agreement) = project := by
  rfl

/-- A recorded transition preserves project identity. -/
theorem history_preserves_projectId
    (project : ProjectState) (agreement : String) :
    (recordAgreementWithHistory project agreement).after.projectId = project.projectId := by
  rfl

/-- A recorded transition preserves branch identity. -/
theorem history_preserves_branchId
    (project : ProjectState) (agreement : String) :
    (recordAgreementWithHistory project agreement).after.branchId = project.branchId := by
  rfl

end YggdrasilLean
