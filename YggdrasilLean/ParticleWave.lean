/-
ParticleWave.lean
Status: expandable-container model; compiler verification pending after this revision.

User-authorized semantic clarification (2026-10-23):
  Particle and Wave are expandable containers that can hold words.
  Their meanings are not restricted to a fixed three-slot tuple.

This model represents container identity separately from the words it holds.
It does not assume that the containers have a fixed length or that their
contents must be interpreted as left operand / operator / right operand.
-/ 

inductive ContainerKind where
  | particle
  | wave
  deriving DecidableEq, Repr

structure WordContainer where
  kind : ContainerKind
  words : List String
  deriving DecidableEq, Repr

def emptyContainer (kind : ContainerKind) : WordContainer :=
  { kind := kind, words := [] }

def appendWord (c : WordContainer) (word : String) : WordContainer :=
  { c with words := c.words ++ [word] }

def appendWords (c : WordContainer) (newWords : List String) : WordContainer :=
  { c with words := c.words ++ newWords }

theorem appendWord_preserves_kind (c : WordContainer) (word : String) :
    (appendWord c word).kind = c.kind := by
  rfl

theorem appendWord_preserves_existing_words
    (c : WordContainer) (word : String) :
    (appendWord c word).words = c.words ++ [word] := by
  rfl

theorem appendWords_preserves_kind
    (c : WordContainer) (newWords : List String) :
    (appendWords c newWords).kind = c.kind := by
  rfl

theorem appendWords_preserves_existing_prefix
    (c : WordContainer) (newWords : List String) :
    c.words ++ newWords = (appendWords c newWords).words := by
  rfl

def particleContainer : WordContainer :=
  { kind := .particle, words := [] }

def waveContainer : WordContainer :=
  { kind := .wave, words := [] }

theorem particle_container_keeps_kind_when_expanded (word : String) :
    (appendWord particleContainer word).kind = .particle := by
  rfl

theorem wave_container_keeps_kind_when_expanded (word : String) :
    (appendWord waveContainer word).kind = .wave := by
  rfl
