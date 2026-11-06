package org.yggdrasil.scheduler.builder;

/** Stable identities for the Nine Builders. These names do not imply separate processes. */
public enum BuilderRole {
    HUGINN("Huginn", "Architecture, objectives, and scope"),
    MUNINN("Muninn", "Memory, continuity, provenance, and recovery"),
    KVASIR("Kvasir", "Research, evidence, and structured knowledge"),
    MARI("Mari", "Relationships, interfaces, integration, and distributed cooperation"),
    HODR("Höðr", "Implementation, execution, and testing"),
    TYR("Týr", "Integrity, permissions, and security"),
    LOKI("Loki", "Alternatives, innovation, and failure recovery"),
    ODIN("Odin", "Integration, reconciliation, and dependencies"),
    BALDR("Baldr", "Independent verification against requirements");

    private final String displayName;
    private final String responsibility;

    BuilderRole(String displayName, String responsibility) {
        this.displayName = displayName;
        this.responsibility = responsibility;
    }

    public String getDisplayName() { return displayName; }
    public String getResponsibility() { return responsibility; }
}