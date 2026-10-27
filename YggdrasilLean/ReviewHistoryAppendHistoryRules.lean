/-!
ReviewHistoryAppendHistoryRules.lean

Status: provisional pure reference rules for classifying an append against a list of
known records. This extends the one-record classifier without claiming that a backend
enforces uniqueness atomically or that the supplied list is complete.
-/

import YggdrasilLean.ReviewHistoryAppendRules

namespace YggdrasilLean

/-- Combine identity classifications; a conflict anywhere in the supplied history wins. -/
def combineIdentityDecisions
    (left right : AppendIdentityDecision) : AppendIdentityDecision :=
  match left, right with
  | .conflict, _ => .conflict
  | _, .conflict => .conflict
  | .alreadyCommitted eventId, _ => .alreadyCommitted eventId
  | _, .alreadyCommitted eventId => .alreadyCommitted eventId
  | .fresh, .fresh => .fresh

/--
Classify an append against every supplied known record. This only reasons over the
provided list; it does not establish that the list is complete or atomically current.
A conflict in any record takes precedence over a possible retry classification.
-/
def classifyAppendAgainstHistory
    (intent : AppendIntent) (history : List KnownAppend) : AppendIdentityDecision :=
  history.foldl
    (fun decision known =>
      combineIdentityDecisions decision
        (classifyAppendIdentity intent (some known)))
    .fresh

/-- An empty known history classifies an intent as fresh. -/
theorem emptyHistory_isFresh (intent : AppendIntent) :
    classifyAppendAgainstHistory intent [] = .fresh := by
  simp [classifyAppendAgainstHistory]

/-- A conflicting identity found anywhere in the supplied history cannot be masked by a retry. -/
theorem conflictDominatesHistory
    (intent : AppendIntent) (prefix suffix : List KnownAppend)
    (known : KnownAppend)
    (hConflict : classifyAppendIdentity intent (some known) = .conflict) :
    classifyAppendAgainstHistory intent (prefix ++ known :: suffix) = .conflict := by
  simp [classifyAppendAgainstHistory, List.foldl_append, combineIdentityDecisions,
    hConflict]
  induction prefix with
  | nil =>
      simp [combineIdentityDecisions, hConflict]
  | cons head tail ih =>
      simp [combineIdentityDecisions, ih]

/-- The one-record history agrees with the existing one-record classifier. -/
theorem singletonHistory_matchesSingleRecord
    (intent : AppendIntent) (known : KnownAppend) :
    classifyAppendAgainstHistory intent [known] =
      classifyAppendIdentity intent (some known) := by
  simp [classifyAppendAgainstHistory, combineIdentityDecisions]

end YggdrasilLean
