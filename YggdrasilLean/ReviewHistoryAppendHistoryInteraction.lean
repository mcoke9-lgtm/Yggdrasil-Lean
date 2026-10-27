/-!
Multi-record append identity interaction checks.
Provisional pure reference checks; not a storage or atomicity proof.
-/

import YggdrasilLean.ReviewHistoryAppendHistoryRules

namespace YggdrasilLean

/-- Current behavior: matching key and payload return the stored event even if intent.eventId differs. -/
theorem sameKeyPayloadDifferentEventId_currentlyRetries
    (intent : AppendIntent) (known : KnownAppend)
    (hProject : intent.projectId = known.projectId)
    (hBranch : intent.branchId = known.branchId)
    (hKey : intent.idempotencyKey = known.idempotencyKey)
    (hPayload : intent.payload = known.payload)
    (_hEvent : intent.eventId ≠ known.eventId) :
    classifyAppendIdentity intent (some known) = .alreadyCommitted known.eventId := by
  exact sameKeySamePayload_isRetry intent known hProject hBranch hKey hPayload

/-- Multiple matching retry records with distinct event IDs are ambiguous in either order. -/
theorem matchingRetryRecords_areAmbiguous
    (intent : AppendIntent) (first second : KnownAppend)
    (hProjectFirst : intent.projectId = first.projectId)
    (hBranchFirst : intent.branchId = first.branchId)
    (hKeyFirst : intent.idempotencyKey = first.idempotencyKey)
    (hPayloadFirst : intent.payload = first.payload)
    (hProjectSecond : intent.projectId = second.projectId)
    (hBranchSecond : intent.branchId = second.branchId)
    (hKeySecond : intent.idempotencyKey = second.idempotencyKey)
    (hPayloadSecond : intent.payload = second.payload)
    (hDifferentEvents : first.eventId ≠ second.eventId) :
    classifyAppendAgainstHistory intent [first, second] = .ambiguousHistory ∧
    classifyAppendAgainstHistory intent [second, first] = .ambiguousHistory := by
  constructor <;>
    simp [classifyAppendAgainstHistory, combineIdentityDecisions,
      classifyAppendIdentity, hProjectFirst, hBranchFirst, hKeyFirst, hPayloadFirst,
      hProjectSecond, hBranchSecond, hKeySecond, hPayloadSecond,
      hDifferentEvents, Ne.symm hDifferentEvents]

/-- Repeating the same matching record identity does not create distinct-ID ambiguity. -/
theorem matchingRetryRecords_sameEventId_isRetry
    (intent : AppendIntent) (first second : KnownAppend)
    (hProjectFirst : intent.projectId = first.projectId)
    (hBranchFirst : intent.branchId = first.branchId)
    (hKeyFirst : intent.idempotencyKey = first.idempotencyKey)
    (hPayloadFirst : intent.payload = first.payload)
    (hProjectSecond : intent.projectId = second.projectId)
    (hBranchSecond : intent.branchId = second.branchId)
    (hKeySecond : intent.idempotencyKey = second.idempotencyKey)
    (hPayloadSecond : intent.payload = second.payload)
    (hSameEvents : first.eventId = second.eventId) :
    classifyAppendAgainstHistory intent [first, second] = .alreadyCommitted first.eventId := by
  simp [classifyAppendAgainstHistory, combineIdentityDecisions,
    classifyAppendIdentity, hProjectFirst, hBranchFirst, hKeyFirst, hPayloadFirst,
    hProjectSecond, hBranchSecond, hKeySecond, hPayloadSecond, hSameEvents]

end YggdrasilLean
