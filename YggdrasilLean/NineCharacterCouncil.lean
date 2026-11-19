/-!
NineCharacterCouncil.lean
Status: bounded Council recommendation model; compiler verification pending.

The Council models differentiated Character voices and produces a recommendation.
It does not authorize or execute external actions. A recommendation to prepare
a Wingbeat is not an instruction to perform one.
-/

import YggdrasilLean.NineCharacterRegistry

namespace YggdrasilLean

/-- The distinct kind of contribution associated with each Character's role. -/
inductive CouncilVoice where
  | coordinateContinuity
  | reviewConsequences
  | assessCapacity
  | identifyTiming
  | offerAlternatives
  | reportHistory
  | submitEvidence
  | identifyBoundaryOptions
  | reportAuthorizationState
  deriving DecidableEq, Repr

/-- Resolve each Character's established role to a distinct Council voice. -/
def voiceOf : CharacterId → CouncilVoice
  | .odin => .coordinateContinuity
  | .baldr => .reviewConsequences
  | .kvasir => .assessCapacity
  | .hodr => .identifyTiming
  | .mari => .offerAlternatives
  | .muninn => .reportHistory
  | .huginn => .submitEvidence
  | .loki => .identifyBoundaryOptions
  | .tyr => .reportAuthorizationState

/-- Canonical Council roster: identity and voice remain explicitly paired. -/
structure CouncilSeat where
  character : CharacterId
  voice : CouncilVoice
  deriving DecidableEq, Repr

def canonicalCouncil : List CouncilSeat :=
  allCharacterIds.map fun id => { character := id, voice := voiceOf id }

/-- Evidence considered before recommending a move beyond the current system. -/
structure WingbeatReadinessEvidence where
  meaningfulBoonBeyondLocal : Bool
  kvasirCapacityWithinLimits : Bool
  returnAndReintegrationPlanPresent : Bool
  proposedFlyingParticipants : List CharacterId
  deriving DecidableEq, Repr

/-- Council recommendations are advisory, never execution permissions. -/
inductive CouncilRecommendation where
  | remainParticle
  | defer (reason : String)
  | prepareWingbeat
  deriving DecidableEq, Repr

/-- A named participant counts as Flying only when the registry says so. -/
def hasFlyingParticipant (registry : List CharacterRecord)
    (participants : List CharacterId) : Bool :=
  participants.any fun id =>
    match findCharacter registry id with
    | some record => record.capability == .flying
    | none => false

/--
The readiness council prefers Particle when no unmet boon requires expansion.
Otherwise it defers on incomplete foundations and only recommends preparation
when capacity, return planning and at least one actual Flying participant exist.
This permits differentiated participation; it does not require all nine to fly.
-/
def assessWingbeatReadiness (registry : List CharacterRecord)
    (evidence : WingbeatReadinessEvidence) : CouncilRecommendation :=
  if registry.map CharacterRecord.id != allCharacterIds then
    .defer "The Nine Character registry is incomplete or out of canonical order."
  else if !evidence.meaningfulBoonBeyondLocal then
    .remainParticle
  else if !evidence.kvasirCapacityWithinLimits then
    .defer "Kvasir's capacity constraint is not satisfied."
  else if !evidence.returnAndReintegrationPlanPresent then
    .defer "A return and reintegration plan is missing."
  else if !hasFlyingParticipant registry evidence.proposedFlyingParticipants then
    .defer "No proposed participant is recorded at Flying capability."
  else
    .prepareWingbeat

/-- A Council recommendation never grants external-action permission. -/
def councilAuthorizesExternalAction (_recommendation : CouncilRecommendation) : Bool :=
  false

theorem canonical_council_has_nine :
    canonicalCouncil.length = 9 := by
  rfl

theorem canonical_council_voices_are_role_mapped :
    canonicalCouncil.map CouncilSeat.voice =
      [.coordinateContinuity, .reviewConsequences, .assessCapacity,
       .identifyTiming, .offerAlternatives, .reportHistory,
       .submitEvidence, .identifyBoundaryOptions, .reportAuthorizationState] := by
  rfl

theorem council_never_authorizes_external_action
    (recommendation : CouncilRecommendation) :
    councilAuthorizesExternalAction recommendation = false := by
  rfl

end YggdrasilLean
