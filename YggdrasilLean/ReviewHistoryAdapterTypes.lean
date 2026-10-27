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
  | ambiguousIdentity
  | conflict
  | unavailable
  | corrupt
  | commitUnknown

/-- Coverage status for a declared identity-lookup scope. -/
inductive LookupCompleteness where
  | complete
  | incomplete
  | unknown
  deriving DecidableEq, Repr

/-- How an identity lookup stopped, including failure after useful observations. -/
inductive LookupTermination where
  | completed
  | stoppedEarly
  | failedAfterObservations
  deriving DecidableEq, Repr

/-- Provenance claimed for a record returned by an identity read. -/
inductive RecordProvenance where
  | storedOriginal
  | reconstructed (methodVersion : String)
  | migrated (migrationVersion : String) (sourceRef : String)
  deriving DecidableEq, Repr

/--
Observed lookup evidence, not a proof that the backend was exhaustive.
Candidate IDs are the distinct IDs actually observed; they are exhaustive only
when completeness is complete and the adapter's trusted boundary warrants that claim.
-/
structure IdentityLookupEvidence (EventId : Type) where
  declaredScope : String
  completeness : LookupCompleteness
  termination : LookupTermination
  observedCandidateEventIds : List EventId

/--
Results when reading one event by identity.
CompleteEvidence is an adapter-issued marker; the type alone does not prove backend
completeness. A checked adapter boundary must control its construction.
-/
inductive IdentityReadOutcome
    (Record EventId CompleteEvidence Diagnostic : Type) where
  | found (record : Record) (provenance : RecordProvenance)
      (evidence : CompleteEvidence)
  | notFound (evidence : CompleteEvidence)
  | incomplete (evidence : IdentityLookupEvidence EventId)
  | unresolved (evidence : IdentityLookupEvidence EventId)
  | ambiguousIdentity (diagnostic : Diagnostic)
      (evidence : IdentityLookupEvidence EventId)
  | unsupportedSchema (version : String)
  | corrupt (diagnostic : Diagnostic)
  | unavailable (diagnostic : Diagnostic)
      (observations : Option (IdentityLookupEvidence EventId))

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
  | ambiguousIdentity
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
