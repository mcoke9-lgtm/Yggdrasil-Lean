/-!
LowerWataInterpreter.lean
Status: pure, bounded interpreter draft; compiler verification pending.

The interpreter evaluates typed instructions against an explicit state and
operator permit. It performs no IO and has no external connectors. Its effects
are state transitions plus append-only in-memory audit entries.
-/

import YggdrasilLean.Wingbeat

namespace YggdrasilLean

/-- Explicit authorization supplied by the operator for this instruction. -/
structure WataPermit where
  operatorApproved : Bool
  deriving DecidableEq, Repr

/-- Why an instruction was rejected. -/
inductive RejectionReason where
  | wrongScale
  | insufficientCapability
  | authorizationRequired
  | invalidJourneyTransition
  | boonNotRecorded
  | boonAlreadyRecorded
  deriving DecidableEq, Repr

/-- Result recorded for every interpreted instruction. -/
inductive ExecutionStatus where
  | accepted
  | rejected (reason : RejectionReason)
  deriving DecidableEq, Repr

/-- Append-only audit entry for one attempted instruction. -/
structure WataAuditEntry where
  instruction : WataInstruction
  status : ExecutionStatus
  beatBefore : Nat
  beatAfter : Nat
  deriving DecidableEq, Repr

/-- State held by the pure interpreter. -/
structure WataRuntimeState where
  model : WingbeatState
  boonRecorded : Bool
  audit : List WataAuditEntry
  deriving DecidableEq, Repr

/-- Higher developmental capabilities retain the earlier capabilities. -/
def canHunt (_capability : CharacterCapability) : Bool := true

def canCoordinate : CharacterCapability → Bool
  | .hunting => false
  | .dancing => true
  | .flying => true

def canFly : CharacterCapability → Bool
  | .hunting => false
  | .dancing => false
  | .flying => true

/-- Apply a permitted command to the model. This function has no external effects. -/
def applyWataCommand (state : WataRuntimeState)
    (command : WataCommand) : WataRuntimeState :=
  match command with
  | .observe => state
  | .hunt => state
  | .distinguish => state
  | .coordinate => state
  | .explore =>
      { state with model := { state.model with journey := .exploration } }
  | .crossBoundary => state
  | .recordBoon =>
      { state with boonRecorded := true }
  | .returnHome =>
      { state with model := { state.model with journey := .return } }
  | .integrateBoon =>
      { state with model := { state.model with journey := .reintegration } }

/-- Determine whether an instruction satisfies the interpreter's bounded policy. -/
def validateWataInstruction (state : WataRuntimeState)
    (instruction : WataInstruction) (permit : WataPermit) :
    Option RejectionReason :=
  if instruction.scale != state.model.scale then
    some .wrongScale
  else
    match instruction.command with
    | .observe => none
    | .hunt =>
        if canHunt state.model.capability then none
        else some .insufficientCapability
    | .distinguish =>
        if canHunt state.model.capability then none
        else some .insufficientCapability
    | .coordinate =>
        if canCoordinate state.model.capability then none
        else some .insufficientCapability
    | .explore => none
    | .crossBoundary =>
        if state.model.scale != .macrocosm then some .wrongScale
        else if !canFly state.model.capability then some .insufficientCapability
        else if !permit.operatorApproved then some .authorizationRequired
        else none
    | .recordBoon =>
        if state.boonRecorded then some .boonAlreadyRecorded
        else if state.model.journey != .transformation then
          some .invalidJourneyTransition
        else none
    | .returnHome =>
        if !state.boonRecorded then some .boonNotRecorded
        else none
    | .integrateBoon =>
        if !state.boonRecorded then some .boonNotRecorded
        else if state.model.journey != .return then
          some .invalidJourneyTransition
        else if !permit.operatorApproved then some .authorizationRequired
        else none

/-- Execute one instruction, always appending an accepted/rejected audit entry. -/
def interpretOne (state : WataRuntimeState)
    (instruction : WataInstruction) (permit : WataPermit) : WataRuntimeState :=
  let decision := validateWataInstruction state instruction permit
  let nextState :=
    match decision with
    | none => applyWataCommand state instruction.command
    | some _ => state
  let status :=
    match decision with
    | none => ExecutionStatus.accepted
    | some reason => ExecutionStatus.rejected reason
  let entry : WataAuditEntry :=
    { instruction := instruction
      status := status
      beatBefore := state.model.beat
      beatAfter := nextState.model.beat }
  { nextState with audit := state.audit ++ [entry] }

/-- Interpret an ordered program using one explicit permit per instruction. -/
def interpretProgram (initial : WataRuntimeState)
    (program : List (WataInstruction × WataPermit)) : WataRuntimeState :=
  program.foldl (fun state item => interpretOne state item.1 item.2) initial

theorem rejected_instruction_preserves_model
    (state : WataRuntimeState) (instruction : WataInstruction)
    (permit : WataPermit) (reason : RejectionReason)
    (h : validateWataInstruction state instruction permit = some reason) :
    (interpretOne state instruction permit).model = state.model := by
  simp [interpretOne, h]

theorem rejected_instruction_preserves_boon
    (state : WataRuntimeState) (instruction : WataInstruction)
    (permit : WataPermit) (reason : RejectionReason)
    (h : validateWataInstruction state instruction permit = some reason) :
    (interpretOne state instruction permit).boonRecorded = state.boonRecorded := by
  simp [interpretOne, h]

theorem interpretOne_appends_one_audit_entry
    (state : WataRuntimeState) (instruction : WataInstruction)
    (permit : WataPermit) :
    (interpretOne state instruction permit).audit.length = state.audit.length + 1 := by
  simp [interpretOne, List.length_append]

end YggdrasilLean
