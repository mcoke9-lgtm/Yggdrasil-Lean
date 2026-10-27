import YggdrasilLean.ParentGraphAudit

/-!
ParentGraphAuditExamples.lean

Executable examples for the current reference classifier. These examples verify
its specified behavior; they do not establish graph-wide cycle detection.
In particular, `.valid` is an external integrity attestation, and `.noCycle`
is conditional on that attestation rather than derived from exhaustive search.
-/

namespace YggdrasilLean

private def triangleEdges : List ParentEdge := [(1, 2), (2, 3), (3, 1)]

/-- A parent target absent from the vertex set makes integrity indeterminate. -/
example :
    integrityFromParentTargets [1] [(1, 2)] = .indeterminate := by
  decide

/-- A dangling parent target cannot yield a no-cycle result through the integrity-aware classifier. -/
example :
    classifyParentGraph [(1, 2)]
      (integrityFromParentTargets [1] [(1, 2)]) none = .indeterminate := by
  decide

/-- A concrete closed path whose every directed edge exists is classified as a cycle. -/
example :
    classifyParentGraph triangleEdges .valid (some [1, 2, 3, 1]) =
      .cycle [1, 2, 3, 1] := by
  decide

/-- A candidate using a missing edge is not accepted as a cycle witness. -/
example :
    isParentCycleWitness [1, 2, 1] triangleEdges = false := by
  decide

/--
Current classifier behavior: with `.valid` integrity, an invalid candidate
returns `.noCycle`. This is a conditional result, not proof that the edge list
contains no other cycle; a graph-wide completeness predicate is still needed.
-/
example :
    classifyParentGraph triangleEdges .valid (some [1, 2, 1]) = .noCycle := by
  decide

/-- Without a cycle witness, indeterminate identity structure remains indeterminate. -/
example :
    classifyParentGraph triangleEdges .indeterminate none = .indeterminate := by
  rfl

/--
No candidate plus externally attested `.valid` integrity yields `.noCycle`;
the classifier itself does not prove that the graph was exhaustively checked.
-/
example :
    classifyParentGraph triangleEdges .valid none = .noCycle := by
  rfl

end YggdrasilLean
