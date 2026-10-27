/-!
ReviewHistoryAppendHistoryConflictTests.lean

Status: proposed pure reference-model regression checks. These are theorem-level checks,
not executable storage integration tests and not evidence of atomic backend enforcement.
-/

import YggdrasilLean.ReviewHistoryAppendHistoryRules

namespace YggdrasilLean

/-- A same-key/different-payload conflict wins when a retry match appears first. -/
theorem sameKeyPayloadConflict_winsAfterRetryMatch
    (intent : AppendIntent) (retry conflict : KnownAppend)
    (hRetry : classifyAppendIdentity intent (some retry) =
      .alreadyCommitted retry.eventId)
    (hProject : intent.projectId = conflict.projectId)
    (hKey : intent.idempotencyKey = conflict.idempotencyKey)
    (hPayload : intent.payload ≠ conflict.payload) :
    classifyAppendAgainstHistory intent [retry, conflict] = .conflict := by
  have hConflict := sameKeyDifferentPayload_conflicts intent conflict hProject hKey hPayload
  exact conflictDominatesHistory intent [retry] [] conflict hConflict

/-- The same-key/different-payload conflict wins when it appears before the retry match. -/
theorem sameKeyPayloadConflict_winsBeforeRetryMatch
    (intent : AppendIntent) (retry conflict : KnownAppend)
    (hRetry : classifyAppendIdentity intent (some retry) =
      .alreadyCommitted retry.eventId)
    (hProject : intent.projectId = conflict.projectId)
    (hKey : intent.idempotencyKey = conflict.idempotencyKey)
    (hPayload : intent.payload ≠ conflict.payload) :
    classifyAppendAgainstHistory intent [conflict, retry] = .conflict := by
  have hConflict := sameKeyDifferentPayload_conflicts intent conflict hProject hKey hPayload
  exact conflictDominatesHistory intent [] [retry] conflict hConflict

/-- An event-ID reuse conflict wins when a retry match appears first. -/
theorem eventIdConflict_winsAfterRetryMatch
    (intent : AppendIntent) (retry conflict : KnownAppend)
    (hRetry : classifyAppendIdentity intent (some retry) =
      .alreadyCommitted retry.eventId)
    (hProject : intent.projectId = conflict.projectId)
    (hKey : intent.idempotencyKey ≠ conflict.idempotencyKey)
    (hEvent : intent.eventId = conflict.eventId) :
    classifyAppendAgainstHistory intent [retry, conflict] = .conflict := by
  have hConflict := sameEventIdDifferentKey_conflicts intent conflict hProject hKey hEvent
  exact conflictDominatesHistory intent [retry] [] conflict hConflict

/-- The event-ID reuse conflict wins when it appears before the retry match. -/
theorem eventIdConflict_winsBeforeRetryMatch
    (intent : AppendIntent) (retry conflict : KnownAppend)
    (hRetry : classifyAppendIdentity intent (some retry) =
      .alreadyCommitted retry.eventId)
    (hProject : intent.projectId = conflict.projectId)
    (hKey : intent.idempotencyKey ≠ conflict.idempotencyKey)
    (hEvent : intent.eventId = conflict.eventId) :
    classifyAppendAgainstHistory intent [conflict, retry] = .conflict := by
  have hConflict := sameEventIdDifferentKey_conflicts intent conflict hProject hKey hEvent
  exact conflictDominatesHistory intent [] [retry] conflict hConflict

end YggdrasilLean
