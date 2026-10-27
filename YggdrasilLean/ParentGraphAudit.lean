/-!
ParentGraphAudit.lean

Formal reference vocabulary for three-state parent-graph analysis.
This is a pure model: it does not parse JavaScript records, validate real storage,
or establish that a supplied edge list is exhaustive.
-/

namespace YggdrasilLean

/-- A directed parent edge from a record identity to its parent identity. -/
abbrev ParentEdge := Nat × Nat

/-- Every consecutive pair in a proposed witness must be an explicit graph edge. -/
def witnessFollowsEdges : List Nat → List ParentEdge → Bool
  | a :: b :: rest, edges =>
      decide ((a, b) ∈ edges) && witnessFollowsEdges (b :: rest) edges
  | _, _ => true

/--
A cycle witness must contain at least two vertices, start and end at the same
identity, and follow explicit directed edges at every step.
-/
def isParentCycleWitness (witness : List Nat) (edges : List ParentEdge) : Bool :=
  match witness with
  | first :: (_ :: _) =>
      decide (witness.getLast? = some first) && witnessFollowsEdges witness edges
  | _ => false

/-- Structural quality of the identities and parent references in the input. -/
inductive ParentGraphIntegrity where
  | valid
  | indeterminate
  deriving DecidableEq, Repr

/-- Every parent target named by an edge is present in the supplied vertex set. -/
def parentTargetsResolve (vertices : List Nat) (edges : List ParentEdge) : Prop :=
  ∀ edge : ParentEdge, edge ∈ edges → edge.2 ∈ vertices

/-- Missing parent targets make structural integrity indeterminate. -/
def integrityFromParentTargets
    (vertices : List Nat) (edges : List ParentEdge) : ParentGraphIntegrity :=
  if h : parentTargetsResolve vertices edges then .valid else .indeterminate

/-- A dangling parent edge cannot be certified as structurally valid. -/
theorem dangling_parent_makes_integrity_indeterminate
    (vertices : List Nat) (edges : List ParentEdge)
    (h : ¬ parentTargetsResolve vertices edges) :
    integrityFromParentTargets vertices edges = .indeterminate := by
  simp [integrityFromParentTargets, h]

/-- Three-way result: only a verified witness establishes a cycle. -/
inductive ParentGraphResult where
  | cycle (witness : List Nat)
  | noCycle
  | indeterminate
  deriving DecidableEq, Repr

/--
Classify using an explicit candidate witness and identity-integrity status.
A candidate is called a cycle only if it checks against the supplied edges.
If identity integrity is indeterminate, absence of a witness is not proof of no cycle.
-/
def classifyParentGraph
    (edges : List ParentEdge)
    (integrity : ParentGraphIntegrity)
    (candidate : Option (List Nat)) : ParentGraphResult :=
  match candidate with
  | some witness =>
      if isParentCycleWitness witness edges then .cycle witness
      else if integrity = .valid then .noCycle
      else .indeterminate
  | none =>
      if integrity = .valid then .noCycle else .indeterminate

/-- Any cycle result carries a witness that validates against the supplied edges. -/
theorem cycle_result_has_valid_witness
    (edges : List ParentEdge) (integrity : ParentGraphIntegrity)
    (candidate : Option (List Nat)) (witness : List Nat)
    (h : classifyParentGraph edges integrity candidate = .cycle witness) :
    isParentCycleWitness witness edges = true := by
  simp [classifyParentGraph] at h
  split at h
  · assumption
  · contradiction

/-- Indeterminate identity structure cannot be classified as no-cycle without a valid witness. -/
theorem indeterminate_without_witness_stays_indeterminate
    (edges : List ParentEdge) :
    classifyParentGraph edges .indeterminate none = .indeterminate := by
  rfl

/-- A malformed candidate is not promoted to a cycle result. -/
theorem invalid_candidate_not_cycle
    (edges : List ParentEdge) (integrity : ParentGraphIntegrity)
    (witness : List Nat)
    (h : isParentCycleWitness witness edges = false) :
    classifyParentGraph edges integrity (some witness) ≠ .cycle witness := by
  simp [classifyParentGraph, h]

end YggdrasilLean
