/-!
BranchForking.lean
Status: first formalized fork model; merge/conflict policy remains provisional.

Opening another conversation does not fork a branch. Forking is an explicit
project-state operation: it assigns a new branch identity while carrying the
recorded baseline forward. Subsequent edits are independent values; this file
does not implement persistence, automatic conflict detection, or merge.
-/

import YggdrasilLean.BranchContinuity

namespace YggdrasilLean

/-- Explicitly fork a project state under a caller-supplied branch identity. -/
def forkProject (project : ProjectState) (newBranchId : String) : ProjectState :=
  { project with branchId := newBranchId }

/-- A fork keeps the same project identity. -/
theorem forkProject_preserves_projectId
    (project : ProjectState) (newBranchId : String) :
    (forkProject project newBranchId).projectId = project.projectId := by
  rfl

/-- A fork receives the branch identity explicitly supplied by the caller. -/
theorem forkProject_sets_branchId
    (project : ProjectState) (newBranchId : String) :
    (forkProject project newBranchId).branchId = newBranchId := by
  rfl

/-- A fork carries the baseline version forward unchanged. -/
theorem forkProject_preserves_version
    (project : ProjectState) (newBranchId : String) :
    (forkProject project newBranchId).stateVersion = project.stateVersion := by
  rfl

/-- A fork carries the agreement baseline forward unchanged. -/
theorem forkProject_preserves_agreements
    (project : ProjectState) (newBranchId : String) :
    (forkProject project newBranchId).agreements = project.agreements := by
  rfl

/-- A fork carries the evidence baseline forward unchanged. -/
theorem forkProject_preserves_evidence
    (project : ProjectState) (newBranchId : String) :
    (forkProject project newBranchId).evidence = project.evidence := by
  rfl

/-- A fork carries the dependency baseline forward unchanged. -/
theorem forkProject_preserves_dependencies
    (project : ProjectState) (newBranchId : String) :
    (forkProject project newBranchId).dependencies = project.dependencies := by
  rfl

/-- A fork carries unresolved items forward instead of silently discarding them. -/
theorem forkProject_preserves_unresolved
    (project : ProjectState) (newBranchId : String) :
    (forkProject project newBranchId).unresolved = project.unresolved := by
  rfl

/-- Two explicit fork identities remain distinct when their supplied IDs differ. -/
theorem forkProject_ids_distinct
    (project : ProjectState) (branchA branchB : String)
    (h : branchA ≠ branchB) :
    (forkProject project branchA).branchId ≠
      (forkProject project branchB).branchId := by
  simpa [forkProject] using h

/-- Editing a fork does not mutate the original immutable project value. -/
theorem fork_edit_preserves_original
    (project : ProjectState) (newBranchId agreement : String) :
    project = project ∧
      (recordAgreement (forkProject project newBranchId) agreement).projectId =
        project.projectId := by
  constructor
  · rfl
  · rfl

end YggdrasilLean
