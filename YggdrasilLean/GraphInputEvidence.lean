import YggdrasilLean.ResolverGraphOutcomeMapping

/-!
GraphInputEvidence.lean

Defines proof obligations tying source record identities to the exact Lean graph
input. These predicates do not themselves establish that a source read is complete;
completeness remains an explicit external premise.
-/

namespace YggdrasilLean

/-- Minimal source row needed to derive a parent graph. -/
structure SourceParentRecord where
  recordId : String
  parentRecordId : Option String
  deriving DecidableEq, Repr

/-- A source identity is paired with its numeric Lean vertex identifier. -/
abbrev SourceVertexBinding := String × Nat

/-- Look up a source identity in the declared mapping, rejecting ambiguous mappings. -/
def mappedVertex (mapping : List SourceVertexBinding) (recordId : String) : Option Nat :=
  match mapping.filter (fun binding => binding.1 == recordId) with
  | [binding] => some binding.2
  | _ => none

/-- Every source record has exactly one mapping and no two source IDs share a vertex ID. -/
def sourceMappingInjective (records : List SourceParentRecord)
    (mapping : List SourceVertexBinding) : Prop :=
  (∀ record ∈ records, ∃ vertex,
    mappedVertex mapping record.recordId = some vertex) ∧
  (∀ left ∈ mapping, ∀ right ∈ mapping,
    left.1 ≠ right.1 → left.2 ≠ right.2)

/-- Vertex IDs derived from the explicit source identity mapping. -/
def mappedVertices (mapping : List SourceVertexBinding) : List Nat :=
  mapping.map Prod.snd

/--
Derive edges from source rows. Each edge is (child vertex, parent vertex).
Rows with a non-empty parent that does not resolve are omitted here and must be
rejected by allParentReferencesResolve; they must never be silently certified.
-/
def mappedParentEdges (records : List SourceParentRecord)
    (mapping : List SourceVertexBinding) : List ParentEdge :=
  records.filterMap fun record =>
    match record.parentRecordId with
    | none => none
    | some parentId =>
      match mappedVertex mapping record.recordId, mappedVertex mapping parentId with
      | some child, some parent => some (child, parent)
      | _, _ => none

/-- Every non-empty source parent reference resolves through the declared mapping. -/
def allParentReferencesResolve (records : List SourceParentRecord)
    (mapping : List SourceVertexBinding) : Prop :=
  ∀ record ∈ records, ∀ parentId,
    record.parentRecordId = some parentId →
      (∃ child, mappedVertex mapping record.recordId = some child) ∧
      (∃ parent, mappedVertex mapping parentId = some parent)

/-- The graph supplied to Lean is exactly the graph derived from the source rows and mapping. -/
def graphMatchesSource (records : List SourceParentRecord)
    (mapping : List SourceVertexBinding) (graph : ParentGraphInput) : Prop :=
  graph.vertices = mappedVertices mapping ∧
  graph.edges = mappedParentEdges records mapping

/-- Source completeness is intentionally an explicit premise, not inferred from graph shape. -/
structure GraphInputEvidence (records : List SourceParentRecord)
    (mapping : List SourceVertexBinding) (graph : ParentGraphInput) : Prop where
  mappingInjective : sourceMappingInjective records mapping
  parentReferencesResolve : allParentReferencesResolve records mapping
  exactGraphBinding : graphMatchesSource records mapping
  sourceScopeComplete : Prop

/-- Exact graph binding entails that the supplied vertices equal the mapping's vertex IDs. -/
theorem graphBinding_vertices_match
    (records : List SourceParentRecord) (mapping : List SourceVertexBinding)
    (graph : ParentGraphInput)
    (h : graphMatchesSource records mapping graph) :
    graph.vertices = mappedVertices mapping := h.1

/-- Exact graph binding entails that the supplied edges equal the source-derived edges. -/
theorem graphBinding_edges_match
    (records : List SourceParentRecord) (mapping : List SourceVertexBinding)
    (graph : ParentGraphInput)
    (h : graphMatchesSource records mapping) :
    graph.edges = mappedParentEdges records mapping := h.2

end YggdrasilLean
