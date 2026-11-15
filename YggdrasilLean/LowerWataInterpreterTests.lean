/-!
LowerWataInterpreterTests.lean
Executable examples and theorem-level regression checks for the bounded interpreter.
-/

import YggdrasilLean.LowerWataInterpreter

namespace YggdrasilLean

def microInstruction (command : WataCommand) : WataInstruction :=
  { scale := .microcosm, command := command }

def noPermit : WataPermit := { operatorApproved := false }
def approvedPermit : WataPermit := { operatorApproved := true }

def heroReturnProgram : List (WataInstruction × WataPermit) :=
  [ (microInstruction .depart, noPermit)
  , (microInstruction .separate, noPermit)
  , (microInstruction .explore, noPermit)
  , (microInstruction .recordBoon, noPermit)
  , (microInstruction .returnHome, noPermit)
  , (microInstruction .integrateBoon, approvedPermit)
  ]

/-- A rejected capability promotion leaves the capability unchanged and is audited. -/
example :
    let result := interpretOne initialWataState
      (microInstruction .learnDance) noPermit
    result.model.capability = .hunting ∧ result.audit.length = 1 := by
  decide

/-- A valid six-step Hero's Journey reaches reintegration and records every step. -/
example :
    let result := interpretProgram initialWataState heroReturnProgram
    result.model.journey = .reintegration ∧
      result.boonRecorded = true ∧ result.audit.length = 6 := by
  decide

/-- A flying-capable system can alternate to macrocosmic scope and increments its beat. -/
example :
    let flyingState :=
      { initialWataState with
        model := { initialWataState.model with capability := .flying } }
    let result := interpretOne flyingState
      (microInstruction .wingbeat) noPermit
    result.model.scale = .macrocosm ∧ result.model.beat = 1 := by
  decide

/-- Cross-boundary instructions require explicit operator approval. -/
example :
    let macroFlyingState :=
      { initialWataState with
        model := { initialWataState.model with
          scale := .macrocosm
          capability := .flying } }
    let denied := interpretOne macroFlyingState
      { scale := .macrocosm, command := .crossBoundary } noPermit
    let allowed := interpretOne macroFlyingState
      { scale := .macrocosm, command := .crossBoundary } approvedPermit
    denied.model = macroFlyingState.model ∧
      allowed.model = macroFlyingState.model ∧
      denied.audit.length = 1 ∧ allowed.audit.length = 1 := by
  decide

end YggdrasilLean
