package org.yggdrasil.scheduler.builder;

import org.junit.Test;
import static org.junit.Assert.*;

public class BuilderCommandRouterTest {
    private final BuilderCommandRouter router = new BuilderCommandRouter();

    @Test public void recognizesNaturalLanguageContinuityRequests() {
        BuilderCommandRouter.Command command = router.route("Please remember that Mari completed the handoff");
        assertEquals(BuilderCommandRouter.Action.RECORD_CONTINUITY_NOTE, command.action);
        assertEquals("Mari completed the handoff", command.argument);
    }

    @Test public void recognizesJournalIntegrityRequests() {
        assertEquals(BuilderCommandRouter.Action.CHECK_JOURNAL, router.route("check memory").action);
        assertEquals(BuilderCommandRouter.Action.CHECK_JOURNAL, router.route("Verify Memory").action);
    }

    @Test public void refusesToPretendUnsupportedActionsAreAvailable() {
        BuilderCommandRouter.Command command = router.route("send an email to the team");
        assertEquals(BuilderCommandRouter.Action.UNSUPPORTED, command.action);
        assertTrue(command.explanation.contains("not connected"));
    }

    @Test public void rejectsBlankInstructions() {
        assertEquals(BuilderCommandRouter.Action.UNSUPPORTED, router.route("  ").action);
        assertEquals(BuilderCommandRouter.Action.UNSUPPORTED, router.route(null).action);
    }
}
