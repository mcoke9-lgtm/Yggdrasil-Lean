/-!
ReviewHistoryAdapterTypes.lean

Status: backend-neutral outcome vocabulary for the durable review-history adapter contract.
These are pure result types only. They do not implement storage, generate identifiers,
enforce atomicity, or establish durability. The adapter contract's distinctions remain
visible instead of collapsing uncertain, unavailable, conflicting, and corrupt states.
-/

namespace YggdrasilLean

/-- Outcomes of an append attempt, parameterized by the adapter's receipt type. -/
inductive AppendOutcome (Receipt : Type) where
  | committed (receipt : Receipt)
  | alreadyCommitted (receipt : Receipt)
  | conflict
  | unavailable
  | corrupt
  | commitUnknown

/-- Results when reading one event by identity. -/
inductive IdentityReadOutcome (Record : Type) where
  | found (record : Record)
  | notFound
  | unsupportedSchema
  | corrupt
  | unavailable

/-- Results of reading an ordered history page. -/
inductive OrderedHistoryOutcome (Record Cursor : Type) where
  | page (records : List Record) (nextCursor : Option Cursor)
  | empty
  | gapOrConflict
  | unsupportedSchema
  | unavailable

/-- Outcomes when resolving an append whose commit status is uncertain. -/
inductive ReconcileOutcome (Receipt : Type) where
  | committed (receipt : Receipt)
  | notCommitted
  | stillUnknown
  | conflict
  | corrupt
  | unavailable

/-- Verification outcomes avoid treating missing support as successful verification. -/
inductive VerificationOutcome where
  | valid
  | invalid
  | incomplete
  | unsupportedSchema
  | unavailable
  deriving DecidableEq, Repr

/--
A fork-lineage description for an adapter to persist or verify.
The optional anchor explicitly represents a missing/unavailable source anchor;
it is not silently replaced with an invented reference.
-/
structure ForkLineage where
  sourceBranchId : String
  destinationBranchId : String
  sourceAnchor : Option String
  destinationGenesisEventId : String
  deriving DecidableEq, Repr

/-- A missing source anchor cannot be mistaken for a present anchor. -/
theorem missingForkAnchor_isExplicit (lineage : ForkLineage)
    (h : lineage.sourceAnchor = none) :
    lineage.sourceAnchor = none := h

/-- Verification is successful only for the explicit valid constructor. -/
def verificationSucceeded : VerificationOutcome → Bool
  | .valid => true
  | .invalid => false
  | .incomplete => false
  | .unsupportedSchema => false
  | .unavailable => false

theorem onlyValidVerification_succeeds (outcome : VerificationOutcome)
    (h : verificationSucceeded outcome = true) :
    outcome = .valid := by
  cases outcome <;> simp [verificationSucceeded] at h ⊢

theorem validVerification_succeeds :
    verificationSucceeded .valid = true := rfl

end YggdrasilLean
