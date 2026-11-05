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
        return BuilderActionPreflight.execute(forAction(observation, "INSTALL_PERIODIC_POLL"), now, action);
    }

    public static BuilderActionPreflight.Execution<Void> offerMonitorRegistration(
            BuilderActionPreflight.Observation observation, Instant now) throws Exception {
        return BuilderActionPreflight.execute(
                forAction(observation, "OFFER_GITHUB_MONITOR_REGISTRATION"), now, () -> null);
    }

    public static <T> BuilderActionPreflight.Execution<T> registerMonitor(
            BuilderActionPreflight.Observation observation, Instant now,
            BuilderActionPreflight.Action<T> action) throws Exception {
        return BuilderActionPreflight.execute(forAction(observation, "REGISTER_GITHUB_MONITOR"), now, action);
    }

    public static <T> BuilderActionPreflight.Execution<T> checkGithubMonitor(
            BuilderActionPreflight.Observation observation, Instant now,
            BuilderActionPreflight.Action<T> action) throws Exception {
        return BuilderActionPreflight.execute(forAction(observation, "CHECK_GITHUB_ACTIONS"), now, action);
    }

    private static BuilderActionPreflight.Observation forAction(
            BuilderActionPreflight.Observation observation, String expectedActionId) {
        return observation != null && expectedActionId.equals(observation.actionId)
                ? observation : null;
    }
}
