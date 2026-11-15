/-!
NineCharacterRegistryTests.lean
Regression examples for canonical identity, role mapping, staged capability
and external-action boundaries.
-/

import YggdrasilLean.NineCharacterRegistry

namespace YggdrasilLean

example : initialCharacterRegistry.length = 9 := by
  decide

example :
    (findCharacter initialCharacterRegistry .odin).map CharacterRecord.role =
      some .coordinationAndReconciliation := by
  decide

example :
    (findCharacter initialCharacterRegistry .huginn).map CharacterRecord.role =
      some .evidence := by
  decide

example :
    (findCharacter initialCharacterRegistry .tyr).map CharacterRecord.role =
      some .authorizationGovernance := by
  decide

example :
    (findCharacter initialCharacterRegistry .loki).map CharacterRecord.capability =
      some .dancing := by
  decide

example :
    (findCharacter (promoteToFlying initialCharacterRegistry .loki true).getD [])
      .loki = none := by
  simp [findCharacter, promoteToFlying, initialCharacterRegistry,
    allCharacterIds, roleOf]

example :
    registryEnablesExternalActions initialCharacterRegistry = false := by
  decide

end YggdrasilLean
