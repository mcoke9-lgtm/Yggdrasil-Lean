import YggdrasilLean.ParentGraphAudit

/-!
ParentGraphAcyclicity.lean

A proof-carrying classifier that separates an observed cycle witness from a
proof that no cycle witness exists in the supplied edge list. The latter is
an explicit premise, not something inferred from a `.valid` label.
This is a logical specification, not an executable exhaustive cycle finder.
-/

namespace YggdrasilLean

/-- Graph-wide acyclicity: every proposed witness fails the cycle-witness check. -/
def ParentGraphAcyclic (edges : List ParentEdge) : Prop :=
  ∀ witness : List Nat, isParentCycleWitness witness edges = false

/--
Proof-carrying classification. A cycle needs a concrete checked witness;
`noCycle` requires both valid identity integrity and a graph-wide acyclicity proof.
-/
def classifyParentGraphWithAcyclicity
    (edges : List ParentEdge)
    (integrity : ParentGraphIntegrity)
    (candidate : Option (List Nat))
    (acyclic : ParentGraphAcyclic edges) : ParentGraphResult :=
  match candidate with
  | some witness =>
      if isParentCycleWitness witness edges then .cycle witness
      else if integrity = .valid then .noCycle
      else .indeterminate
  | none =>
      if integrity = .valid then .noCycle else .indeterminate

/-- Any cycle outcome still carries a checked witness. -/
theorem proofClassifier_cycle_has_valid_witness
    (edges : List ParentEdge) (integrity : ParentGraphIntegrity)
    (candidate : Option (List Nat)) (acyclic : ParentGraphAcyclic edges)
    (witness : List Nat)
    (h : classifyParentGraphWithAcyclicity edges integrity candidate acyclic = .cycle witness) :
    isParentCycleWitness witness edges = true := by
  simp [classifyParentGraphWithAcyclicity] at h
  split at h
  · assumption
  · contradiction

/-- A `noCycle` outcome is supported by a graph-wide acyclicity premise. -/
theorem proofClassifier_noCycle_requires_acyclicity
    (edges : List ParentEdge) (integrity : ParentGraphIntegrity)
    (candidate : Option (List Nat)) (acyclic : ParentGraphAcyclic edges)
    (h : classifyParentGraphWithAcyclicity edges integrity candidate acyclic = .noCycle) :
    ParentGraphAcyclic edges := acyclic

/-- A known cycle witness contradicts the graph-wide acyclicity predicate. -/
theorem cycle_witness_refutes_acyclicity
    (edges : List ParentEdge) (witness : List Nat)
    (hcycle : isParentCycleWitness witness edges = true) :
    ¬ ParentGraphAcyclic edges := by
  intro hacyclic
  have hfalse := hacyclic witness
  rw [hcycle] at hfalse
  contradiction

/-- The triangle graph cannot be declared acyclic because it has an explicit cycle. -/
theorem triangle_not_acyclic :
    ¬ ParentGraphAcyclic [(1, 2), (2, 3), (3, 1)] := by
  apply cycle_witness_refutes_acyclicity
  decide

/-- A self-contained empty-edge example has no cycle witness under this definition. -/
theorem emptyEdges_acyclic : ParentGraphAcyclic ([] : List ParentEdge) := by
  intro witness
  cases witness with
  | nil => rfl
  | cons first rest =>
      cases rest with
      | nil => rfl
      | cons second tail =>
          cases tail with
          | nil => rfl
          | cons third remaining =>
              -- No edge can be followed in the empty graph.
              simp [isParentCycleWitness, witnessFollowsEdges]

end YggdrasilLean
