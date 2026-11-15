/-!
NineCharacterCouncilTests.lean
Regression examples for differentiated Council voices and readiness gates.
-/

import YggdrasilLean.NineCharacterCouncil

namespace YggdrasilLean

def noBoonEvidence : WingbeatReadinessEvidence :=
  { meaningfulBoonBeyondLocal := false
    kvasirCapacityWithinLimits := true
    returnAndReintegrationPlanPresent := true
    proposedFlyingParticipants := [] }

def readyEvidence : WingbeatReadinessEvidence :=
  { meaningfulBoonBeyondLocal := true
    kvasirCapacityWithinLimits := true
    returnAndReintegrationPlanPresent := true
    proposedFlyingParticipants := [.loki] }

example : canonicalCouncil.length = 9 := by
  decide

example :
    (canonicalCouncil.map CouncilSeat.voice).length = 9 := by
  decide

example :
    assessWingbeatReadiness initialCharacterRegistry noBoonEvidence =
      .remainParticle := by
  decide

example :
    assessWingbeatReadiness initialCharacterRegistry
      { readyEvidence with kvasirCapacityWithinLimits := false } =
        .defer "Kvasir's capacity constraint is not satisfied." := by
  decide

example :
    assessWingbeatReadiness initialCharacterRegistry
      { readyEvidence with returnAndReintegrationPlanPresent := false } =
        .defer "A return and reintegration plan is missing." := by
  decide

example :
    assessWingbeatReadiness initialCharacterRegistry readyEvidence =
      .defer "No proposed participant is recorded at Flying capability." := by
  decide

example :
    assessWingbeatReadiness
      ((promoteToFlying initialCharacterRegistry .loki true).getD [])
      readyEvidence = .prepareWingbeat := by
  decide

/-- A single Flying participant is sufficient; the other eight need not fly. -/
example :
    ((promoteToFlying initialCharacterRegistry .loki true).getD []).filter
      (fun record => record.capability == .flying) |>.length = 1 := by
  decide

example :
    councilAuthorizesExternalAction .prepareWingbeat = false := by
  decide

end YggdrasilLean
