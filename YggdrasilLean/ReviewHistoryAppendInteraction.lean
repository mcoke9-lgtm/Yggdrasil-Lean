/-!
ReviewHistoryAppendInteraction.lean

Status: provisional pure interaction model and regression theorems.
Identity is classified before expected-position checks so an already-committed retry
can be recognized even when its original expected position is now stale. This is not
a storage adapter and does not establish atomicity or concurrent-writer behavior.
-/ 

import YggdrasilLean.ReviewHistoryAppendRules

namespace YggdrasilLean

/-- Result of applying identity classification before position validation. -/
inductive AppendAttemptDecision where
  | retryResolved (existingEventId : String)
  | identityConflict
  | freshAtExpectedPosition
  | stalePosition
  deriving DecidableEq, Repr

/--
Resolve a known retry/conflict first. Only a fresh append is subject to the current
expected-position check. This avoids rejecting a successful prior append as stale
merely because the branch has advanced since that append.
-/ 
def classifyAppendAttempt
    (intent : AppendIntent)
    (known : Option KnownAppend)
    (observedPosition : Nat) : AppendAttemptDecision :=
  match classifyAppendIdentity intent known with
  | .alreadyCommitted eventId => .retryResolved eventId
  | .conflict => .identityConflict
  | .fresh =>
      match checkExpectedPosition intent.expectedPosition observedPosition with
      | .matches => .freshAtExpectedPosition
      | .stale => .stalePosition

/-- A known matching retry resolves even if its original expected position is stale. -/
theorem retry_resolves_before_stale_position
    (intent : AppendIntent) (known : KnownAppend) (observedPosition : Nat)
    (hProject : intent.projectId = known.projectId)
    (hBranch : intent.branchId = known.branchId)
    (hKey : intent.idempotencyKey = known.idempotencyKey)
    (hPayload : intent.payload = known.payload)
    (hStale : intent.expectedPosition ≠ observedPosition) :
    classifyAppendAttempt intent (some known) observedPosition =
      .retryResolved known.eventId := by
  simp [classifyAppendAttempt, sameKeySamePayload_isRetry
    intent known hProject hBranch hKey hPayload]

/-- A conflicting reuse is not converted into a stale-position result. -/
theorem identity_conflict_precedes_position_check
    (intent : AppendIntent) (known : KnownAppend) (observedPosition : Nat)
    (hProject : intent.projectId = known.projectId)
    (hKey : intent.idempotencyKey = known.idempotencyKey)
    (hPayload : intent.payload ≠ known.payload) :
    classifyAppendAttempt intent (some known) observedPosition =
      .identityConflict := by
  simp [classifyAppendAttempt, sameKeyDifferentPayload_conflicts
    intent known hProject hKey hPayload]

/-- A fresh identity still requires the expected position to match. -/
theorem fresh_append_requires_matching_position
    (intent : AppendIntent) (observedPosition : Nat)
    (hFresh : classifyAppendIdentity intent none = .fresh)
    (hPosition : intent.expectedPosition ≠ observedPosition) :
    classifyAppendAttempt intent none observedPosition = .stalePosition := by
  simp [classifyAppendAttempt, hFresh, checkExpectedPosition, hPosition]

end YggdrasilLean
