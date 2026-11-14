package org.yggdrasil.memorygate;

import org.junit.Test;
import java.util.Collections;
import java.util.HashSet;
import static org.junit.Assert.assertEquals;

public class AuthorizationDecisionTest {
    private static MemoryAccessGrant validGrant() {
        return new MemoryAccessGrant("request-1", "recover one record", "continuity",
                new HashSet<>(Collections.singletonList("project-fact-001")),
                2_000L, 1_500L);
    }

    @Test public void allowsOnlyAnExplicitlyPermittedRecord() {
        assertEquals(AuthorizationDecision.Outcome.ALLOW,
                AuthorizationDecision.evaluate(validGrant(), "project-fact-001", 1_000L));
        assertEquals(AuthorizationDecision.Outcome.DENY,
                AuthorizationDecision.evaluate(validGrant(), "decision-history-001", 1_000L));
    }

    @Test public void deniesExpiredOrStoppedGrants() {
        assertEquals(AuthorizationDecision.Outcome.DENY,
                AuthorizationDecision.evaluate(validGrant(), "project-fact-001", 2_000L));
        assertEquals(AuthorizationDecision.Outcome.DENY,
                AuthorizationDecision.evaluate(validGrant(), "project-fact-001", 1_500L));
    }

    @Test public void deniesMissingOrIncompleteGrants() {
        assertEquals(AuthorizationDecision.Outcome.DENY,
                AuthorizationDecision.evaluate(null, "project-fact-001", 1_000L));
        MemoryAccessGrant incomplete = new MemoryAccessGrant(
                "request-2", "", "continuity",
                Collections.singleton("project-fact-001"), 2_000L, 1_500L);
        assertEquals(AuthorizationDecision.Outcome.DENY,
                AuthorizationDecision.evaluate(incomplete, "project-fact-001", 1_000L));
    }
}
