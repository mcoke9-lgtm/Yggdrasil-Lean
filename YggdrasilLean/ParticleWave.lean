/-!
ParticleWave.lean
Status: draft; compiler verification pending.

Recorded working hypothesis:
  Wave:     (TERA, FIRA, WATA)
  Particle: (TERA, WATA, FIRA)

Positions are ordered left operand, operator, right operand.
A position's category label is separate from its semantic identity.
-/ 

inductive Category where
  | TERA
  | FIRA
  | WATA
  | AIRA
  deriving DecidableEq, Repr

inductive SemanticIdentity where
  | leftValue
  | operatorValue
  | rightValue
  | other (id : Nat)
  deriving DecidableEq, Repr

structure SlotValue where
  label : Category
  meaning : SemanticIdentity
  deriving DecidableEq, Repr

structure Representation where
  left : SlotValue
  operator : SlotValue
  right : SlotValue
  deriving DecidableEq, Repr

def waveLabels : Representation :=
  { left := { label := .TERA, meaning := .leftValue }
    operator := { label := .FIRA, meaning := .operatorValue }
    right := { label := .WATA, meaning := .rightValue } }

def particleLabels : Representation :=
  { left := { label := .TERA, meaning := .leftValue }
    operator := { label := .WATA, meaning := .operatorValue }
    right := { label := .FIRA, meaning := .rightValue } }

def hasWaveLabels (x : Representation) : Prop :=
  x.left.label = .TERA ∧
  x.operator.label = .FIRA ∧
  x.right.label = .WATA

def hasParticleLabels (x : Representation) : Prop :=
  x.left.label = .TERA ∧
  x.operator.label = .WATA ∧
  x.right.label = .FIRA

-- Exchange FIRA/WATA, fixing TERA/AIRA.
def swapCategory : Category → Category
  | .TERA => .TERA
  | .AIRA => .AIRA
  | .FIRA => .WATA
  | .WATA => .FIRA

def swapSlotLabel (x : SlotValue) : SlotValue :=
  { x with label := swapCategory x.label }

def swapLabels (x : Representation) : Representation :=
  { left := swapSlotLabel x.left
    operator := swapSlotLabel x.operator
    right := swapSlotLabel x.right }

-- Exchange operands while preserving the operator slot.
def exchangeOperands (x : Representation) : Representation :=
  { left := x.right
    operator := x.operator
    right := x.left }

theorem swapSlotLabel_preserves_meaning (x : SlotValue) :
    (swapSlotLabel x).meaning = x.meaning := by
  rfl

theorem swapCategory_involutive (c : Category) :
    swapCategory (swapCategory c) = c := by
  cases c <;> rfl

theorem swapLabels_involutive (x : Representation) :
    swapLabels (swapLabels x) = x := by
  cases x with
  | mk l o r =>
    cases l with
    | mk ll lm =>
      cases o with
      | mk ol om =>
        cases r with
        | mk rl rm =>
          cases ll <;> cases ol <;> cases rl <;> rfl

theorem exchangeOperands_involutive (x : Representation) :
    exchangeOperands (exchangeOperands x) = x := by
  cases x <;> rfl

theorem swapLabels_commutes_with_operand_exchange
    (x : Representation) :
    swapLabels (exchangeOperands x) =
      exchangeOperands (swapLabels x) := by
  cases x with
  | mk l o r =>
    cases l with
    | mk ll lm =>
      cases o with
      | mk ol om =>
        cases r with
        | mk rl rm =>
          cases ll <;> cases ol <;> cases rl <;> rfl

theorem wave_has_recorded_labels :
    hasWaveLabels waveLabels := by
  exact ⟨rfl, rfl, rfl⟩

theorem particle_has_recorded_labels :
    hasParticleLabels particleLabels := by
  exact ⟨rfl, rfl, rfl⟩

theorem swapping_wave_labels_gives_particle :
    hasParticleLabels (swapLabels waveLabels) := by
  exact ⟨rfl, rfl, rfl⟩

theorem swapping_particle_labels_gives_wave :
    hasWaveLabels (swapLabels particleLabels) := by
  exact ⟨rfl, rfl, rfl⟩

theorem exchanged_wave_is_not_wave :
    ¬ hasWaveLabels (exchangeOperands waveLabels) := by
  intro h
  exact Category.noConfusion h.2.2
