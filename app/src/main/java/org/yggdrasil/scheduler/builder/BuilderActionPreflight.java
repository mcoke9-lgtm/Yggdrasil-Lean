package org.yggdrasil.scheduler.builder;

import java.time.Duration;
import java.time.Instant;

/**
 * Deterministic fail-closed preflight for project-owned external-action boundaries.
 *
 * Observations describe the executor/action being considered; they are not model
 * assertions and must be refreshed at the point of use. This gate cannot intercept
 * ChatGPT or another application's host-level actions.
 */
public final class BuilderActionPreflight {
    public enum CapabilityState { AVAILABLE, BLOCKED, UNKNOWN }
    public enum Authorization { GRANTED, NOT_REQUIRED, DENIED, UNKNOWN }
    public enum Decision { PROCEED, BLOCKED, RECHECK_REQUIRED }

    public static final class Observation {
        public final String executorId;
        public final String actionId;
        public final CapabilityState state;
        public final Instant observedAt;
        public final Duration maximumAge;
        public final Long maximumCapacity;
        public final Long currentUsage;
        public final Long remainingCapacity;
        public final Authorization authorization;
        public final String evidenceReference;

        public Observation(String executorId, String actionId,
                           CapabilityState state, Instant observedAt,
                           Duration maximumAge, Long maximumCapacity,
                           Long currentUsage, Long remainingCapacity,
                           Authorization authorization, String evidenceReference) {
            this.executorId = executorId;
            this.actionId = actionId;
            this.state = state;
            this.observedAt = observedAt;
            this.maximumAge = maximumAge;
            this.maximumCapacity = maximumCapacity;
            this.currentUsage = currentUsage;
            this.remainingCapacity = remainingCapacity;
            this.authorization = authorization;
            this.evidenceReference = evidenceReference;
        }
    }

    public interface Action<T> {
        T run() throws Exception;
    }

    public static final class Execution<T> {
        public final Decision decision;
        public final T value;
        public final String reason;
        public final boolean executed;

        private Execution(Decision decision, T value, String reason, boolean executed) {
            this.decision = decision;
            this.value = value;
            this.reason = reason;
            this.executed = executed;
        }
    }

    private BuilderActionPreflight() {}

    public static Decision evaluate(Observation observation, Instant now) {
        if (observation == null || now == null
                || blank(observation.executorId) || blank(observation.actionId)
                || blank(observation.evidenceReference)
                || observation.state == null || observation.authorization == null
                || observation.observedAt == null || observation.maximumAge == null
                || observation.maximumAge.isNegative()) {
            return Decision.RECHECK_REQUIRED;
        }

        Duration age = Duration.between(observation.observedAt, now);
        if (age.isNegative() || age.compareTo(observation.maximumAge) > 0)
            return Decision.RECHECK_REQUIRED;

        if (observation.authorization == Authorization.DENIED)
            return Decision.BLOCKED;
        if (observation.authorization == Authorization.UNKNOWN)
            return Decision.RECHECK_REQUIRED;

        boolean anyCapacity = observation.maximumCapacity != null
                || observation.currentUsage != null || observation.remainingCapacity != null;
        if (anyCapacity) {
            if (observation.maximumCapacity == null || observation.currentUsage == null
                    || observation.remainingCapacity == null)
                return Decision.RECHECK_REQUIRED;
            long maximum = observation.maximumCapacity;
            long used = observation.currentUsage;
            long remaining = observation.remainingCapacity;
            if (maximum < 0 || used < 0 || remaining < 0 || used > maximum
                    || maximum - used != remaining)
                return Decision.RECHECK_REQUIRED;
            if (remaining == 0) return Decision.BLOCKED;
        }

        if (observation.state == CapabilityState.BLOCKED) return Decision.BLOCKED;
        if (observation.state != CapabilityState.AVAILABLE)
            return Decision.RECHECK_REQUIRED;
        return Decision.PROCEED;
    }

    /** The action cannot run unless a fresh observation passes immediately beforehand. */
    public static <T> Execution<T> execute(Observation observation, Instant now,
                                           Action<T> action) throws Exception {
        if (action == null)
            return new Execution<>(Decision.RECHECK_REQUIRED, null,
                    "No action implementation is available.", false);
        Decision decision = evaluate(observation, now);
        if (decision != Decision.PROCEED) {
            String reason = decision == Decision.BLOCKED
                    ? "The selected executor/action is blocked by current evidence or authorization."
                    : "Executor/action capability evidence is missing, stale, or inconsistent.";
            return new Execution<>(decision, null, reason, false);
        }
        return new Execution<>(Decision.PROCEED, action.run(),
                "Fresh preflight passed for " + observation.executorId + "/" + observation.actionId
                        + " using " + observation.evidenceReference, true);
    }

    private static boolean blank(String value) {
        return value == null || value.trim().isEmpty();
    }
}
