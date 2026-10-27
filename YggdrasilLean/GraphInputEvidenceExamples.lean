import YggdrasilLean.GraphInputEvidence

/-!
GraphInputEvidenceExamples.lean

Small executable examples of source-to-graph binding and the separation between
internal graph structure and an external source-completeness premise.
-/

namespace YggdrasilLean

private def exampleRecords : List SourceParentRecord :=
  [⟨"A", none⟩, ⟨"B", some "A"⟩]

private def exampleMapping : List SourceVertexBinding :=
  [("A", 1), ("B", 2)]

private def exampleGraph : ParentGraphInput :=
  ⟨[1, 2], [(2, 1)]⟩

/-- Source IDs map to their declared numeric vertices. -/
example : mappedVertex exampleMapping "A" = some 1 := by
  decide

example : mappedVertex exampleMapping "B" = some 2 := by
  decide

/-- The derived child-to-parent edge agrees with the graph supplied to Lean. -/
example : mappedParentEdges exampleRecords exampleMapping = [(2, 1)] := by
  decide

example : graphMatchesSource exampleRecords exampleMapping exampleGraph := by
  constructor <;> decide

/-- A missing non-empty parent reference cannot resolve through the mapping. -/
example : mappedVertex exampleMapping "MISSING" = none := by
  decide

/-- With the missing target, the source-reference resolution obligation fails. -/
theorem missing_parent_target_fails_resolution :
    ¬ allParentReferencesResolve
      [⟨"A", none⟩, ⟨"B", some "MISSING"⟩]
      exampleMapping := by
  intro h
  have resolved := h ⟨"B", some "MISSING"⟩ (by simp) "MISSING" rfl
  obtain ⟨_, parentExists⟩ := resolved
  obtain ⟨parent, parentEq⟩ := parentExists
  simp [mappedVertex, exampleMapping] at parentEq

/-- An extra mapping entry is detectable even if the resulting graph is well-formed. -/
theorem extra_mapping_entry_breaks_exact_domain :
    ¬ mappingDomainMatchesRecords exampleRecords
      [("A", 1), ("B", 2), ("EXTRA", 3)] := by
  intro h
  have extraBinding := h.1 ("EXTRA", 3) (by simp)
  obtain ⟨record, recordIn, recordIdEq⟩ := extraBinding
  simp [exampleRecords] at recordIn
  subst recordIdEq
  simp at recordIn

/-- Evidence carries source completeness as its own premise. -/
theorem graph_input_evidence_preserves_completeness_premise
    (records : List SourceParentRecord)
    (mapping : List SourceVertexBinding)
    (graph : ParentGraphInput)
    (evidence : GraphInputEvidence records mapping graph) :
    evidence.sourceScopeComplete := evidence.sourceScopeComplete

end YggdrasilLean
