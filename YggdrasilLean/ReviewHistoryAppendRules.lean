/-!
ReviewHistoryAppendRules.lean

Status: provisional pure reference rules for idempotency identity and expected-position checks.
These definitions do not perform I/O and do not prove that a backend enforces the rules
atomically under concurrency. The corresponding adapter and integration tests remain future work.
-/

namespace YggdrasilLean

/-- The identity and logical payload of an append attempt. -/
structure AppendIntent where
  projectId : String
  branchId : String
  eventId : String
  idempotencyKey : String
  expectedPosition : Nat
  payload : String
  deriving DecidableEq, Repr

/-- The identity and payload of an already known append. -/
structure KnownAppend where
  projectId : String
  branchId : String
  eventId : String
  idempotencyKey : String
  position : Nat
  payload : String
  deriving DecidableEq, Repr

/-- Pure classification of identity reuse; it is not a storage operation. -/
inductive AppendIdentityDecision where
  | fresh
  | alreadyCommitted (existingEventId : String)
  | ambiguousHistory
  | conflict
  deriving DecidableEq, Repr

/--
Classify an append against one known record.
A project-scoped matching idempotency key with the same branch and payload is a retry.
Reusing that key with different content, or reusing an event ID inconsistently, conflicts.
Payload equality here is exact String equality, not a settled canonical schema equality.
-/
def classifyAppendIdentity
    (intent : AppendIntent) (known : Option KnownAppend) : AppendIdentityDecision :=
  match known with
  | none => .fresh
  | some existing =>
      if intent.projectId != existing.projectId then
        .fresh
      else if intent.idempotencyKey == existing.idempotencyKey then
        if intent.branchId == existing.branchId && intent.payload == existing.payload then
          .alreadyCommitted existing.eventId
        else
          .conflict
      else if intent.eventId == existing.eventId then
        .conflict
      else
        .fresh

/-- Compare an expected branch position with the observed position. -/
inductive ExpectedPositionDecision where
  | matches
  | stale
  deriving DecidableEq, Repr

def checkExpectedPosition (expected observed : Nat) : ExpectedPositionDecision :=
  if expected == observed then .matches else .stale

/-- Matching positions are classified as matching. -/
theorem expectedPosition_match (position : Nat) :
    checkExpectedPosition position position = .matches := by
  simp [checkExpectedPosition]

/-- Any distinct expected and observed positions are classified as stale. -/
theorem expectedPosition_distinct_isStale (expected observed : Nat)
    (h : expected ≠ observed) :
    checkExpectedPosition expected observed = .stale := by
  simp [checkExpectedPosition, h]

/-- A retry with the same project, branch, key, and payload resolves to the known event. -/
theorem sameKeySamePayload_isRetry
    (intent : AppendIntent) (known : KnownAppend)
    (hProject : intent.projectId = known.projectId)
    (hBranch : intent.branchId = known.branchId)
    (hKey : intent.idempotencyKey = known.idempotencyKey)
    (hPayload : intent.payload = known.payload) :
    classifyAppendIdentity intent (some known) =
      .alreadyCommitted known.eventId := by
  simp [classifyAppendIdentity, hProject, hBranch, hKey, hPayload]

/-- Reusing a project-scoped key for a different payload conflicts. -/
theorem sameKeyDifferentPayload_conflicts
    (intent : AppendIntent) (known : KnownAppend)
    (hProject : intent.projectId = known.projectId)
    (hKey : intent.idempotencyKey = known.idempotencyKey)
    (hPayload : intent.payload ≠ known.payload) :
    classifyAppendIdentity intent (some known) = .conflict := by
  simp [classifyAppendIdentity, hProject, hKey, hPayload]

/-- Reusing an event ID under a different key conflicts rather than creating another event. -/
theorem sameEventIdDifferentKey_conflicts
    (intent : AppendIntent) (known : KnownAppend)
    (hProject : intent.projectId = known.projectId)
    (hKey : intent.idempotencyKey ≠ known.idempotencyKey)
    (hEvent : intent.eventId = known.eventId) :
    classifyAppendIdentity intent (some known) = .conflict := by
  simp [classifyAppendIdentity, hProject, hKey, hEvent]

end YggdrasilLean
