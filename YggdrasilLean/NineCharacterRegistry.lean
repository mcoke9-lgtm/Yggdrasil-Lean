/-!
NineCharacterRegistry.lean
Status: canonical identity/role registry draft; compiler verification pending.

This is a pure data model. It does not instantiate agents, grant autonomy,
or enable external actions. All nine Characters begin at Dancing capability,
reflecting the adopted Hunting -> Dancing -> Flying progression.
Flight promotion is individual and requires an explicit operator approval flag.
-/

import YggdrasilLean.Wingbeat

namespace YggdrasilLean

/-- Stable identities for the Nine Characters. -/
inductive CharacterId where
  | odin
  | baldr
  | kvasir
  | hodr
  | mari
  | muninn
  | huginn
  | loki
  | tyr
  deriving DecidableEq, Repr, BEq

/-- The distinct coordinating function associated with each Character. -/
inductive CharacterRole where
  | coordinationAndReconciliation
  | review
  | limitsAndCapacity
  | scheduling
  | alternativesAndDisagreement
  | historyAndContinuity
  | evidence
  | capabilityBoundariesAndRecovery
  | authorizationGovernance
  deriving DecidableEq, Repr

/-- Map each stable identity to its established role. -/
def roleOf : CharacterId → CharacterRole
  | .odin => .coordinationAndReconciliation
  | .baldr => .review
  | .kvasir => .limitsAndCapacity
  | .hodr => .scheduling
  | .mari => .alternativesAndDisagreement
  | .muninn => .historyAndContinuity
  | .huginn => .evidence
  | .loki => .capabilityBoundariesAndRecovery
  | .tyr => .authorizationGovernance

/-- Canonical registry order; identity is not inferred from list position. -/
def allCharacterIds : List CharacterId :=
  [.odin, .baldr, .kvasir, .hodr, .mari, .muninn, .huginn, .loki, .tyr]

/-- A Character's registry record. Role and capability are separate dimensions. -/
structure CharacterRecord where
  id : CharacterId
  role : CharacterRole
  capability : CharacterCapability
  deriving DecidableEq, Repr

/-- The adopted starting registry: all have learned to hunt and dance. -/
def initialCharacterRegistry : List CharacterRecord :=
  allCharacterIds.map fun id =>
    { id := id, role := roleOf id, capability := .dancing }

/-- Look up a Character without relying on its list position. -/
def findCharacter (registry : List CharacterRecord)
    (id : CharacterId) : Option CharacterRecord :=
  registry.find? fun record => record.id == id

/-- Promote one Character from Dancing to Flying only with operator approval. -/
def promoteToFlying (registry : List CharacterRecord)
    (id : CharacterId) (operatorApproved : Bool) :
    Option (List CharacterRecord) :=
  if !operatorApproved then
    none
  else
    match findCharacter registry id with
    | none => none
    | some record =>
        if record.capability != .dancing then
          none
        else
          some (registry.map fun current =>
            if current.id == id then
              { current with capability := .flying }
            else current)

/-- Character roles may propose or assess; they do not themselves authorize external action. -/
inductive CharacterDecision where
  | observe
  | propose
  | review
  | recommend
  deriving DecidableEq, Repr

/-- Only the operator's explicit permit can satisfy this external-action gate. -/
def externalActionPermitted (operatorApproved : Bool) : Bool :=
  operatorApproved

/-- The registry itself never grants external action capability. -/
def registryEnablesExternalActions (_registry : List CharacterRecord) : Bool :=
  false

theorem canonical_registry_has_nine :
    initialCharacterRegistry.length = 9 := by
  rfl

theorem canonical_registry_ids_are_complete :
    initialCharacterRegistry.map CharacterRecord.id = allCharacterIds := by
  rfl

theorem promotion_without_approval_changes_nothing
    (id : CharacterId) :
    promoteToFlying initialCharacterRegistry id false = none := by
  simp [promoteToFlying]

theorem promotion_is_individual
    (id : CharacterId) (approved : Bool)
    (h : approved = true) :
    match promoteToFlying initialCharacterRegistry id approved with
    | some registry =>
        (findCharacter registry id).map CharacterRecord.capability = some .flying
    | none => False := by
  cases id <;> simp [promoteToFlying, findCharacter, h, initialCharacterRegistry,
    allCharacterIds, roleOf]

theorem registry_never_enables_external_actions :
    registryEnablesExternalActions initialCharacterRegistry = false := by
  rfl

end YggdrasilLean
