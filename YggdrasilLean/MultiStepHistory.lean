/-!
MultiStepHistory.lean
Status: multi-step history kernel; repository persistence and branch merges remain out of scope.

The history keeps its origin and appends each transition from the current state.
This models an in-memory sequence of recorded edits, not durable storage by itself.
-/

import YggdrasilLean.HistoryReversibility

namespace YggdrasilLean

/-- A project state together with its origin and ordered transition history. -/
structure AgreementHistory where
  origin : ProjectState
  current : ProjectState
  transitions : List TransitionRecord
  deriving DecidableEq, Repr

/-- Begin a history with an origin state and no transitions. -/
def startHistory (project : ProjectState) : AgreementHistory :=
  { origin := project, current := project, transitions := [] }

/-- Append one agreement edit to the current end of the history. -/
def appendAgreement (history : AgreementHistory) (agreement : String) : AgreementHistory :=
  let event := recordAgreementWithHistory history.current agreement
  { history with
    current := event.after
    transitions := history.transitions ++ [event] }

/-- Appending an edit preserves the original starting state. -/
theorem appendAgreement_preserves_origin
    (history : AgreementHistory) (agreement : String) :
    (appendAgreement history agreement).origin = history.origin := by
  rfl

/-- The new transition starts at the state current before the edit. -/
theorem appendAgreement_event_before
    (history : AgreementHistory) (agreement : String) :
    (recordAgreementWithHistory history.current agreement).before = history.current := by
  rfl

/-- The current state after an edit is the resulting state recorded by that event. -/
theorem appendAgreement_current_is_event_after
    (history : AgreementHistory) (agreement : String) :
    (appendAgreement history agreement).current =
      (recordAgreementWithHistory history.current agreement).after := by
  rfl

/-- Starting from a history's origin, two edits retain the same origin and two events. -/
theorem two_edits_preserve_origin_and_record_two
    (project : ProjectState) (first second : String) :
    let history := appendAgreement (appendAgreement (startHistory project) first) second
    history.origin = project /\ history.transitions.length = 2 := by
  simp [appendAgreement, startHistory, recordAgreementWithHistory, recordAgreement]

/-- The origin is always available as a recovery point in the history record. -/
def recoverHistoryOrigin (history : AgreementHistory) : ProjectState :=
  history.origin

/-- Recovery from the history returns the exact state at which the history began. -/
theorem recoverHistoryOrigin_exact
    (project : ProjectState) (history : AgreementHistory) :
    recoverHistoryOrigin (startHistory project) = project := by
  rfl

end YggdrasilLean
