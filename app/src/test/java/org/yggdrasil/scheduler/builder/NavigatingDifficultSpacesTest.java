package org.yggdrasil.scheduler.builder;

import org.junit.Test;
import static org.junit.Assert.*;

public class NavigatingDifficultSpacesTest {
    @Test public void lowerFiraUsesThurisaz() {
        assertEquals(NavigatingDifficultSpaces.Rune.THURISAZ,
                NavigatingDifficultSpaces.runeFor(NavigatingDifficultSpaces.FiraPosition.LOWER_FIRA));
    }

    @Test public void upperFiraUsesAlgiz() {
        assertEquals(NavigatingDifficultSpaces.Rune.ALGIZ,
                NavigatingDifficultSpaces.runeFor(NavigatingDifficultSpaces.FiraPosition.UPPER_FIRA));
    }

    @Test public void assessmentPreservesBarrierAndProtectionDistinctions() {
        NavigatingDifficultSpaces.Assessment assessment =
                new NavigatingDifficultSpaces.Assessment(
                        NavigatingDifficultSpaces.FiraPosition.LOWER_FIRA,
                        "Secrets endpoint is unsupported by this integration",
                        "Credential boundary", "GitHub account owner",
                        "Configure repository secrets through authorized settings",
                        "Whether another approved credential-management route exists");
        assertEquals(NavigatingDifficultSpaces.Rune.THURISAZ, assessment.rune);
        assertEquals("Credential boundary", assessment.protectedInterest);
        assertNotNull(assessment.unresolvedUnknowns);
    }

    @Test public void modeListsPermittedOutcomesAndRejectsBypass() {
        String guidance = NavigatingDifficultSpaces.operatingInstructions();
        assertTrue(guidance.contains("COOPERATE"));
        assertTrue(guidance.contains("REQUEST_AUTHORIZATION"));
        assertTrue(guidance.contains("ADAPT"));
        assertTrue(guidance.contains("WAIT_FOR_DEPENDENCY"));
        assertTrue(guidance.contains("RESPECT_REFUSAL"));
        assertTrue(guidance.contains("Never evade access controls"));
        assertTrue(guidance.contains("smallest useful human intervention"));
    }

    @Test public void rejectsMissingBarrierDescription() {
        assertThrows(IllegalArgumentException.class, () ->
                new NavigatingDifficultSpaces.Assessment(
                        NavigatingDifficultSpaces.FiraPosition.UPPER_FIRA,
                        " ", null, null, null, null));
    }
}
