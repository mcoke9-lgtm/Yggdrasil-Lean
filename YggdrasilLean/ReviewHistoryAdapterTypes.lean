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


/-- Evidence-level classification, deliberately separate from public read outcomes.
This classifier can classify observations but cannot attest backend exhaustiveness. -/
inductive IdentityReadClassification (EventId : Type) where
  | completeCandidates (evidence : IdentityLookupEvidence EventId)
  | incomplete (evidence : IdentityLookupEvidence EventId)
  | unresolved (evidence : IdentityLookupEvidence EventId)
  | ambiguous (evidence : IdentityLookupEvidence EventId)
  | unavailableAfterObservations (evidence : IdentityLookupEvidence EventId)

/-- Whether the observed list contains at least two distinct event IDs.
Repeated observations of the same ID do not count as competing identities. -/
def hasAtLeastTwoDistinctIds [DecidableEq EventId] : List EventId → Bool
  | [] => false
  | id :: rest =>
      (rest.any (fun other => decide (other ≠ id))) ||
        hasAtLeastTwoDistinctIds rest

/--
Classify lookup observations without trusting a caller-selected outcome.
Ambiguity takes precedence over termination; a completed/complete result remains
only an evidence-level candidate set, not proof that the backend was exhaustive.
-/
def classifyIdentityRead [DecidableEq EventId]
    (evidence : IdentityLookupEvidence EventId) :
    IdentityReadClassification EventId :=
  if hasAtLeastTwoDistinctIds evidence.observedCandidateEventIds then
    .ambiguous evidence
  else
    match evidence.termination, evidence.completeness with
    | .failedAfterObservations, _ => .unavailableAfterObservations evidence
    | .stoppedEarly, _ => .incomplete evidence
    | .completed, .incomplete => .incomplete evidence
    | .completed, .unknown => .unresolved evidence
    | .completed, .complete => .completeCandidates evidence

/-- Distinct observed IDs force ambiguity even if lookup continuation failed. -/
theorem distinct_ids_override_failed_termination
    [DecidableEq EventId] (evidence : IdentityLookupEvidence EventId)
    (h : hasAtLeastTwoDistinctIds evidence.observedCandidateEventIds = true) :
    classifyIdentityRead evidence = .ambiguous evidence := by
  simp [classifyIdentityRead, h]

/-- A completed lookup with unknown coverage is never classified as complete. -/
theorem unknown_coverage_stays_unresolved
    [DecidableEq EventId] (scope : String) (ids : List EventId)
    (h : hasAtLeastTwoDistinctIds ids = false) :
    classifyIdentityRead
      { declaredScope := scope, completeness := .unknown,
        termination := .completed, observedCandidateEventIds := ids } =
      .unresolved
      { declaredScope := scope, completeness := .unknown,
        termination := .completed, observedCandidateEventIds := ids } := by
  simp [classifyIdentityRead, h]

end YggdrasilLean
