package org.yggdrasil.scheduler.builder;

import org.junit.Test;
import static org.junit.Assert.assertEquals;

public class BuilderDisclosurePolicyTest {
    @Test public void routineSubmissionUsesExistingPersistentConsent() {
        assertEquals(BuilderDisclosurePolicy.Decision.NO_PROMPT,
                BuilderDisclosurePolicy.decision(false, true));
    }

    @Test public void routineSubmissionRequiresPersistentConsentWhenNoneExists() {
        assertEquals(BuilderDisclosurePolicy.Decision.PERSISTENT_CONSENT_REQUIRED,
                BuilderDisclosurePolicy.decision(false, false));
    }

    @Test public void sensitiveSubmissionAlwaysRequiresFreshOneTimeConsent() {
        assertEquals(BuilderDisclosurePolicy.Decision.ONE_TIME_CONSENT_REQUIRED,
                BuilderDisclosurePolicy.decision(true, false));
        assertEquals(BuilderDisclosurePolicy.Decision.ONE_TIME_CONSENT_REQUIRED,
                BuilderDisclosurePolicy.decision(true, true));
    }
}
