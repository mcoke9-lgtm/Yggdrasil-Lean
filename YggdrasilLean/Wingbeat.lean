/-!
Wingbeat.lean
Status: first formal specification draft; compiler verification pending.

Purpose:
  Model Hunting -> Dancing -> Flying as a developmental progression,
  and the Wingbeat as alternation between microcosmic and macrocosmic scope.

Both the Particle-Wave-Particle cycle and the Hero's Journey are represented
at either scope. Scope is independent of process phase.

This file defines a small Lower WATA instruction vocabulary. It is a typed
representation, not yet a runtime, scheduler, agent, or authorization system.
-/

namespace YggdrasilLean

/-- The scale at which a process is being considered. -/
inductive Scale where
  | microcosm
  | macrocosm
  deriving DecidableEq, Repr

/-- Move to the complementary scale. -/
def otherScale : Scale → Scale
  | .microcosm => .macrocosm
  | .macrocosm => .microcosm

theorem otherScale_ne (s : Scale) : otherScale s ≠ s := by
  cases s <;> decide

/-- The three phases of a Particle-Wave-Particle cycle. -/
inductive CyclePhase where
  | particleStart
  | wave
  | particleResult
  deriving DecidableEq, Repr

/-- Advance one phase, restarting after the resulting Particle. -/
def nextCyclePhase : CyclePhase → CyclePhase
  | .particleStart => .wave
  | .wave => .particleResult
  | .particleResult => .particleStart

/-- The Hero's Journey, ordered independently of scale and cycle phase. -/
inductive HeroStage where
  | departure
  | separation
  | exploration
  | transformation
  | return
  | reintegration
  deriving DecidableEq, Repr

/-- Developmental capabilities: hunting, dancing, then flying. -/
inductive CharacterCapability where
  | hunting
  | dancing
  | flying
  deriving DecidableEq, Repr

/-- A minimal typed vocabulary for the proposed Lower WATA language. -/
inductive WataCommand where
  | observe
  | hunt
  | distinguish
  | coordinate
  | explore
  | crossBoundary
  | recordBoon
  | returnHome
  | integrateBoon
  deriving DecidableEq, Repr

/-- One instruction names its scope explicitly; no implicit scale switching. -/
structure WataInstruction where
  scale : Scale
  command : WataCommand
  deriving DecidableEq, Repr

/-- A Lower WATA program is an ordered sequence of typed instructions. -/
abbrev WataProgram := List WataInstruction

/-- Current state of the Wingbeat model. -/
structure WingbeatState where
  scale : Scale
  cycle : CyclePhase
  journey : HeroStage
  capability : CharacterCapability
  beat : Nat
  deriving DecidableEq, Repr

/--
One wingbeat alternates scope while preserving the current cycle phase,
Hero's Journey stage, and developmental capability.
-/
def wingbeat (state : WingbeatState) : WingbeatState :=
  { state with
    scale := otherScale state.scale
    beat := state.beat + 1
  }

theorem wingbeat_changes_scale (state : WingbeatState) :
    (wingbeat state).scale ≠ state.scale := by
  exact otherScale_ne state.scale

theorem wingbeat_preserves_cycle (state : WingbeatState) :
    (wingbeat state).cycle = state.cycle := by
  rfl

theorem wingbeat_preserves_journey (state : WingbeatState) :
    (wingbeat state).journey = state.journey := by
  rfl

theorem wingbeat_preserves_capability (state : WingbeatState) :
    (wingbeat state).capability = state.capability := by
  rfl

end YggdrasilLean
