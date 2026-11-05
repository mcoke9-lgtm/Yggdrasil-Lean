package org.yggdrasil.scheduler.builder;

/**
 * Chooses whether a submission needs a consent prompt.
 *
 * Persistent consent covers routine submissions within the approved route and
 * disclosure scope. A user-declared sensitive submission always requires a
 * fresh, one-time approval, even when persistent consent exists.
 */
public final class BuilderDisclosurePolicy {
    public enum Decision {
        NO_PROMPT,
        PERSISTENT_CONSENT_REQUIRED,
        ONE_TIME_CONSENT_REQUIRED
    }

    private BuilderDisclosurePolicy() {}

    public static Decision decision(boolean sensitiveOrConfidential, boolean persistentConsentExists) {
        if (sensitiveOrConfidential) return Decision.ONE_TIME_CONSENT_REQUIRED;
        return persistentConsentExists ? Decision.NO_PROMPT : Decision.PERSISTENT_CONSENT_REQUIRED;
    }
}
