/-!
ReviewHistoryAppendHistoryConflictTests.lean

Status: proposed pure reference-model regression checks. These are theorem-level checks,
not executable storage integration tests and not evidence of atomic backend enforcement.
-/

import YggdrasilLean.ReviewHistoryAppendHistoryRules

namespace YggdrasilLean

/--
If one known record is a retry match and another is a same-key/different-payload
conflict, the conflict must win when the retry match appears first.
-/
theorem sameKeyPayloadConflict_winsAfterRetryMatch
    (intent : AppendIntent) (retry conflict : KnownAppend)
    (hRetry : classifyAppendIdentity intent (some retry) =
      .alreadyCommitted retry.eventId)
    (hConflict : classifyAppendIdentity intent (some conflict) = .conflict) :
    classifyAppendAgainstHistory intent [retry, conflict] = .conflict := by
  exact conflictDominatesHistory intent [retry] [] conflict hConflict

/--
The same conflict must win when the conflicting record appears before the retry match.
-/
theorem sameKeyPayloadConflict_winsBeforeRetryMatch
    (intent : AppendIntent) (retry conflict : KnownAppend)
    (hRetry : classifyAppendIdentity intent (some retry) =
      .alreadyCommitted retry.eventId)
    (hConflict : classifyAppendIdentity intent (some conflict) = .conflict) :
    classifyAppendAgainstHistory intent [conflict, retry] = .conflict := by
  exact conflictDominatesHistory intent [] [retry] conflict hConflict

/--
If one known record is a retry match and another conflicts because the event ID
is reused under a different key, conflict wins in either order (retry first).
-/
theorem eventIdConflict_winsAfterRetryMatch
    (intent : AppendIntent) (retry conflict : KnownAppend)
    (hRetry : classifyAppendIdentity intent (some retry) =
      .alreadyCommitted retry.eventId)
    (hConflict : classifyAppendIdentity intent (some conflict) = .conflict) :
    classifyAppendAgainstHistory intent [retry, conflict] = .conflict := by
  exact conflictDominatesHistory intent [retry] [] conflict hConflict

/--
The event-ID conflict also wins when the conflicting record appears before the retry match.
-/
theorem eventIdConflict_winsBeforeRetryMatch
    (intent : AppendIntent) (retry conflict : KnownAppend)
    (hRetry : classifyAppendIdentity intent (some retry) =
      .alreadyCommitted retry.eventId)
    (hConflict : classifyAppendIdentity intent (some conflict) = .conflict) :
    classifyAppendAgainstHistory intent [conflict, retry] = .conflict := by
  exact conflictDominatesHistory intent [] [retry] conflict hConflict

end YggdrasilLean
