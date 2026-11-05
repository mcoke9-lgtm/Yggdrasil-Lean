package org.yggdrasil.scheduler.builder;

import org.junit.Test;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.Assert.*;

public class BuilderActionPreflightTest {
    private static final Instant NOW = Instant.parse("2026-11-05T18:00:00Z");

    private BuilderActionPreflight.Observation observation(
            BuilderActionPreflight.CapabilityState state,
            BuilderActionPreflight.Authorization authorization,
            Long maximum, Long used, Long remaining, Instant observedAt) {
        return new BuilderActionPreflight.Observation(
                "yggdrasil-local-scheduler", "REGISTER_GITHUB_MONITOR",
                state, observedAt, Duration.ofMinutes(2),
                maximum, used, remaining, authorization,
                "runtime://local-scheduler/readiness-check");
    }

    @Test public void freshAvailableAuthorizedExecutorMayProceed() {
        assertEquals(BuilderActionPreflight.Decision.PROCEED,
                BuilderActionPreflight.evaluate(observation(
                        BuilderActionPreflight.CapabilityState.AVAILABLE,
                        BuilderActionPreflight.Authorization.GRANTED,
                        null, null, null, NOW), NOW));
    }

    @Test public void knownBlockedExecutorIsBlocked() {
        assertEquals(BuilderActionPreflight.Decision.BLOCKED,
                BuilderActionPreflight.evaluate(observation(
                        BuilderActionPreflight.CapabilityState.BLOCKED,
                        BuilderActionPreflight.Authorization.GRANTED,
                        null, null, null, NOW), NOW));
    }

    @Test public void fullCapacityBlocksBeforeActionRuns() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        BuilderActionPreflight.Execution<String> result =
                BuilderActionPreflight.execute(observation(
                        BuilderActionPreflight.CapabilityState.AVAILABLE,
                        BuilderActionPreflight.Authorization.GRANTED,
                        3L, 3L, 0L, NOW), NOW, () -> {
                    calls.incrementAndGet();
                    return "should not happen";
                });
        assertEquals(BuilderActionPreflight.Decision.BLOCKED, result.decision);
        assertFalse(result.executed);
        assertEquals(0, calls.get());
    }

    @Test public void staleEvidenceRequiresRecheckAndDoesNotRunAction() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        BuilderActionPreflight.Execution<Void> result =
                BuilderActionPreflight.execute(observation(
                        BuilderActionPreflight.CapabilityState.AVAILABLE,
                        BuilderActionPreflight.Authorization.GRANTED,
                        null, null, null, NOW.minusSeconds(121)), NOW, () -> {
                    calls.incrementAndGet();
                    return null;
                });
        assertEquals(BuilderActionPreflight.Decision.RECHECK_REQUIRED, result.decision);
        assertFalse(result.executed);
        assertEquals(0, calls.get());
    }

    @Test public void unknownCapabilityCannotBeTreatedAsAvailable() {
        assertEquals(BuilderActionPreflight.Decision.RECHECK_REQUIRED,
                BuilderActionPreflight.evaluate(observation(
                        BuilderActionPreflight.CapabilityState.UNKNOWN,
                        BuilderActionPreflight.Authorization.GRANTED,
                        null, null, null, NOW), NOW));
    }

    @Test public void unknownAuthorizationRequiresRecheck() {
        assertEquals(BuilderActionPreflight.Decision.RECHECK_REQUIRED,
                BuilderActionPreflight.evaluate(observation(
                        BuilderActionPreflight.CapabilityState.AVAILABLE,
                        BuilderActionPreflight.Authorization.UNKNOWN,
                        null, null, null, NOW), NOW));
    }

    @Test public void deniedAuthorizationBlocks() {
        assertEquals(BuilderActionPreflight.Decision.BLOCKED,
                BuilderActionPreflight.evaluate(observation(
                        BuilderActionPreflight.CapabilityState.AVAILABLE,
                        BuilderActionPreflight.Authorization.DENIED,
                        null, null, null, NOW), NOW));
    }

    @Test public void inconsistentCapacityRequiresRecheck() {
        assertEquals(BuilderActionPreflight.Decision.RECHECK_REQUIRED,
                BuilderActionPreflight.evaluate(observation(
                        BuilderActionPreflight.CapabilityState.AVAILABLE,
                        BuilderActionPreflight.Authorization.GRANTED,
                        5L, 2L, 1L, NOW), NOW));
    }

    @Test public void successfulActionRunsExactlyOnceAfterPreflight() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        BuilderActionPreflight.Execution<String> result =
                BuilderActionPreflight.execute(observation(
                        BuilderActionPreflight.CapabilityState.AVAILABLE,
                        BuilderActionPreflight.Authorization.GRANTED,
                        5L, 2L, 3L, NOW), NOW, () -> {
                    calls.incrementAndGet();
                    return "queued";
                });
        assertEquals(BuilderActionPreflight.Decision.PROCEED, result.decision);
        assertTrue(result.executed);
        assertEquals("queued", result.value);
        assertEquals(1, calls.get());
    }

    @Test public void futureObservationRequiresRecheck() {
        assertEquals(BuilderActionPreflight.Decision.RECHECK_REQUIRED,
                BuilderActionPreflight.evaluate(observation(
                        BuilderActionPreflight.CapabilityState.AVAILABLE,
                        BuilderActionPreflight.Authorization.GRANTED,
                        null, null, null, NOW.plusSeconds(1)), NOW));
    }
}
