package org.yggdrasil.scheduler.builder;

/**
 * Structured operating mode for interacting with technical, institutional,
 * interpersonal, or informational boundaries without defeating their protections.
 */
public final class NavigatingDifficultSpaces {
    public enum FiraPosition { LOWER_FIRA, UPPER_FIRA }
    public enum Rune { THURISAZ, ALGIZ }
    public enum Outcome {
        COOPERATE, REQUEST_AUTHORIZATION, ADAPT, WAIT_FOR_DEPENDENCY, RESPECT_REFUSAL
    }

    public static final class Assessment {
        public final FiraPosition position;
        public final Rune rune;
        public final String observedBarrier;
        public final String protectedInterest;
        public final String authorityOrOwner;
        public final String permittedInteraction;
        public final String unresolvedUnknowns;

        public Assessment(FiraPosition position, String observedBarrier,
                          String protectedInterest, String authorityOrOwner,
                          String permittedInteraction, String unresolvedUnknowns) {
            if (position == null) throw new IllegalArgumentException("FIRA position is required");
            this.position = position;
            this.rune = position == FiraPosition.LOWER_FIRA ? Rune.THURISAZ : Rune.ALGIZ;
            this.observedBarrier = required(observedBarrier, "observedBarrier");
            this.protectedInterest = optional(protectedInterest);
            this.authorityOrOwner = optional(authorityOrOwner);
            this.permittedInteraction = optional(permittedInteraction);
            this.unresolvedUnknowns = optional(unresolvedUnknowns);
        }
    }

    private NavigatingDifficultSpaces() {}

    public static Rune runeFor(FiraPosition position) {
        if (position == null) throw new IllegalArgumentException("FIRA position is required");
        return position == FiraPosition.LOWER_FIRA ? Rune.THURISAZ : Rune.ALGIZ;
    }

    /** Guidance appended to a user-directed task when this mode is enabled. */
    public static String operatingInstructions() {
        return "NINE BUILDERS OPERATING MODE: NAVIGATING DIFFICULT SPACES.\n"
            + "Purpose: interact with protection and boundaries, not bypass or defeat them.\n"
            + "LOWER FIRA / THURISAZ: identify the encountered barrier; document its observable "
            + "behavior, scope, severity, protected interest if known, and permitted interface. "
            + "Do not infer motive from the barrier alone.\n"
            + "UPPER FIRA / ALGIZ: identify the protective authority, owner, steward, or system "
            + "that maintains the boundary; determine its stated purpose, legitimate authorization "
            + "path, and conditions for cooperation. Do not presume authority where it is unknown.\n"
            + "Keep observed facts separate from inference and unknowns. Record who controls missing "
            + "information and what evidence or authorized request could resolve it.\n"
            + "Investigate documented interfaces, public documentation, supported APIs, diagnostics, "
            + "logs available to us, and legitimate support channels. Never evade access controls, "
            + "exploit vulnerabilities, impersonate an authorized party, expose credentials, or "
            + "weaken protections to obtain access.\n"
            + "Choose and explain one outcome: COOPERATE, REQUEST_AUTHORIZATION, ADAPT, "
            + "WAIT_FOR_DEPENDENCY, or RESPECT_REFUSAL. Preserve work and offer a safe next step.\n"
            + "Report what the Builders can do, what requires the user's authorization or physical "
            + "action, and the smallest useful human intervention. Do not transfer routine technical "
            + "work to the user merely because a boundary exists.\n"
            + "When a human action is necessary, create a HANDOFF addressed to USER. Explain in ordinary conversation: "
            + "the exact action needed, why the Builders cannot perform it, what protection or authority requires it, "
            + "the safe place to perform it, what confirmation to report, and what the Builders will do immediately afterward. "
            + "Ask for one necessary action at a time. Do not send the user away to read a checklist or technical document "
            + "when the Builders can guide the action conversationally. Never ask the user to paste secrets into chat.\n"
            + "The user remains director and retains final authority for consequential commitments.";
    }

    private static String required(String value, String name) {
        if (value == null || value.trim().isEmpty())
            throw new IllegalArgumentException(name + " must not be blank");
        return value.trim();
    }

    private static String optional(String value) {
        return value == null || value.trim().isEmpty() ? null : value.trim();
    }
}
