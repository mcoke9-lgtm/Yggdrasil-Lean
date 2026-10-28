import YggdrasilLean.ParentGraphAcyclicity

/-!
ResolverGraphOutcomeMapping.lean

Contract for comparing the JavaScript analyzeParentGraph result with the Lean
reference model. This is deliberately conditional: it does not claim that the
two implementations are equivalent for arbitrary inputs.

JavaScript behavior observed in reference-model/review-history-resolver.mjs:
* returns CYCLE as soon as it detects a cycle in the usable recordId -> parentRecordId map;
* otherwise returns INDETERMINATE when malformed/duplicate record identities, malformed
  parent references, or unresolved non-empty parent references were observed;
* otherwise returns NO_CYCLE.

Important scope gaps: the JavaScript scan skips malformed IDs, treats duplicate IDs as
indeterminate but continues scanning, and marks an unresolved non-empty parent reference
indeterminate. A detected cycle can take precedence over unrelated malformed records or
unresolved parent references. The Lean edge list must be built from the same usable identities
and parent links before any comparison. Completeness of that edge list is a separate obligation.
-/

namespace YggdrasilLean

/-- Names of the three graph-level outcomes returned by the JavaScript analyzer. -/
inductive ResolverGraphOutcome where
  | cycle
  | noCycle
  | indeterminate
  deriving DecidableEq, Repr

/-- Map the Lean proof-carrying classification to the JavaScript outcome vocabulary. -/
def resolverOutcomeOf : ParentGraphResult → ResolverGraphOutcome
  | .cycle _ => .cycle
  | .noCycle => .noCycle
  | .indeterminate => .indeterminate

/-- A mapped cycle outcome retains a concrete Lean witness in its source result. -/
theorem mapped_cycle_has_source_witness
    (result : ParentGraphResult) (h : resolverOutcomeOf result = .cycle) :
    ∃ witness, result = .cycle witness := by
  cases result with
  | cycle witness => exact ⟨witness, rfl⟩
  | noCycle => simp [resolverOutcomeOf] at h
  | indeterminate => simp [resolverOutcomeOf] at h

/--
Interpretation rule for a sound `NO_CYCLE` comparison: require valid identities,
an edge list faithful to the analyzed parent links, and a proof of acyclicity.
The JS analyzer does not itself return such a proof; a bridge/validation step must supply it.
-/
structure NoCycleCorrespondencePremises (edges : List ParentEdge) : Prop where
  /-- The adapter has established unique, well-formed identities and parent references. -/
  identitiesValid : Prop
  /-- The edge list faithfully represents the analyzed input's usable parent links. -/
  edgeListFaithful : Prop
  /-- A proof that no witness validates as a cycle in this edge list. -/
  acyclic : ParentGraphAcyclic edges

/-- A correspondence certificate is explicitly conditional on all three premises. -/
theorem noCycle_comparison_requires_certificate
    (edges : List ParentEdge) (certificate : NoCycleCorrespondencePremises edges) :
    ParentGraphAcyclic edges := certificate.acyclic

/--
Malformed/ambiguous input must not be silently treated as a certified no-cycle result.
This is a contract rule for the integration layer, not a claim that the JS implementation
currently returns identical outcomes in every mixed-error case.
-/
def mayCertifyNoCycle (identityIntegrityCertain : Bool) (edgeListComplete : Bool) : Bool :=
  identityIntegrityCertain && edgeListComplete

theorem cannot_certify_no_cycle_when_integrity_uncertain (edgeListComplete : Bool) :
    mayCertifyNoCycle false edgeListComplete = false := by
  cases edgeListComplete <;> rfl

theorem cannot_certify_no_cycle_when_edges_incomplete (identityIntegrityCertain : Bool) :
    mayCertifyNoCycle identityIntegrityCertain false = false := by
  cases identityIntegrityCertain <;> rfl

end YggdrasilLean
