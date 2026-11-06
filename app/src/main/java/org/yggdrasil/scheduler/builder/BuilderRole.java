package org.yggdrasil.scheduler.builder;

/** Stable identities for the Nine Builders. These names do not imply separate processes. */
public enum BuilderRole {
    HUGINN("Huginn", "Evidence, source verification, and research"),
    MUNINN("Muninn", "Persistence, history, continuity, provenance, and recovery"),
    KVASIR("Kvasir", "Operational constraints, capability limits, and unsuitable-method detection"),
    MARI("Mari", "Complementary alternatives, relationships, interfaces, and integration"),
    HODR("Höðr", "Service runner, GitHub monitoring, and execution operations"),
    TYR("Týr", "Authorization, permissions, and security"),
    LOKI("Loki", "Interruption, recovery, time-zone/DST handling, and duplicate suppression"),
    ODIN("Odin", "Coordination, orchestration, and integration"),
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