/-!
BranchContinuity.lean
Status: first formalized continuity kernel; conceptual scope remains provisional.

A conversation is a work location, not a project branch. Changing only the
conversation location must preserve the project state. A project edit is a
separate state transition and must preserve project and branch identity.
-/ 

namespace YggdrasilLean

structure ProjectState where
  projectId : String
  branchId : String
  stateVersion : Nat
  agreements : List String
  evidence : List String
  dependencies : List String
  unresolved : List String
  deriving DecidableEq, Repr

structure WorkSnapshot where
  project : ProjectState
  conversationId : String
  deriving DecidableEq, Repr

/-- Change the conversation in which work is happening, without changing project state. -/
def moveConversation (snapshot : WorkSnapshot) (newConversationId : String) : WorkSnapshot :=
  { snapshot with conversationId := newConversationId }

/-- Record an agreement as a project edit, increasing the state version. -/
def recordAgreement (project : ProjectState) (agreement : String) : ProjectState :=
  { project with
    stateVersion := project.stateVersion + 1
    agreements := project.agreements ++ [agreement] }

/-- Changing conversations alone preserves project identity. -/
theorem moveConversation_preserves_projectId
    (snapshot : WorkSnapshot) (newConversationId : String) :
    (moveConversation snapshot newConversationId).project.projectId =
      snapshot.project.projectId := by
  rfl

/-- Changing conversations alone preserves branch identity. -/
theorem moveConversation_preserves_branchId
    (snapshot : WorkSnapshot) (newConversationId : String) :
    (moveConversation snapshot newConversationId).project.branchId =
      snapshot.project.branchId := by
  rfl

/-- Changing conversations alone preserves the complete recorded project state. -/
theorem moveConversation_preserves_project
    (snapshot : WorkSnapshot) (newConversationId : String) :
    (moveConversation snapshot newConversationId).project = snapshot.project := by
  rfl

/-- Recording an agreement does not change project identity. -/
theorem recordAgreement_preserves_projectId
    (project : ProjectState) (agreement : String) :
    (recordAgreement project agreement).projectId = project.projectId := by
  rfl

/-- Recording an agreement does not change branch identity. -/
theorem recordAgreement_preserves_branchId
    (project : ProjectState) (agreement : String) :
    (recordAgreement project agreement).branchId = project.branchId := by
  rfl

/-- Recording an agreement increments the state version exactly once. -/
theorem recordAgreement_increments_version
    (project : ProjectState) (agreement : String) :
    (recordAgreement project agreement).stateVersion = project.stateVersion + 1 := by
  rfl

/-- Recording an agreement retains the previous agreement list as a prefix. -/
theorem recordAgreement_preserves_agreement_prefix
    (project : ProjectState) (agreement : String) :
    (recordAgreement project agreement).agreements = project.agreements ++ [agreement] := by
  rfl

end YggdrasilLean
