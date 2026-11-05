package org.yggdrasil.scheduler.builder;

import java.time.Instant;

/**
 * Named, testable adapters for the Android application's real action boundaries.
 * Production callers must use the boundary matching the action being attempted.
 */
public final class BuilderActionBoundaries {
    private BuilderActionBoundaries() {}

    public static BuilderActionPreflight.Execution<Void> installPeriodicPoll(
            BuilderActionPreflight.Observation observation, Instant now,
            BuilderActionPreflight.Action<Void> action) throws Exception {
        return BuilderActionPreflight.execute(observation, now, action);
    }

    public static BuilderActionPreflight.Execution<Void> offerMonitorRegistration(
            BuilderActionPreflight.Observation observation, Instant now) throws Exception {
        return BuilderActionPreflight.execute(observation, now, () -> null);
    }

    public static <T> BuilderActionPreflight.Execution<T> registerMonitor(
            BuilderActionPreflight.Observation observation, Instant now,
            BuilderActionPreflight.Action<T> action) throws Exception {
        return BuilderActionPreflight.execute(observation, now, action);
    }

    public static <T> BuilderActionPreflight.Execution<T> checkGithubMonitor(
            BuilderActionPreflight.Observation observation, Instant now,
            BuilderActionPreflight.Action<T> action) throws Exception {
        return BuilderActionPreflight.execute(observation, now, action);
    }
}
