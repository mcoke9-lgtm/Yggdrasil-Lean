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
        return observation("REGISTER_GITHUB_MONITOR", state, authorization,
                maximum, used, remaining, observedAt);
    }

    private BuilderActionPreflight.Observation observation(
            String actionId, BuilderActionPreflight.CapabilityState state,
            BuilderActionPreflight.Authorization authorization,
            Long maximum, Long used, Long remaining, Instant observedAt) {
        return new BuilderActionPreflight.Observation(
                "yggdrasil-local-scheduler", actionId,
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

    @Test public void periodicPollInstallBoundarySuppressesBlockedAction() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        BuilderActionPreflight.Execution<Void> result =
                BuilderActionBoundaries.installPeriodicPoll(observation("INSTALL_PERIODIC_POLL",
                        BuilderActionPreflight.CapabilityState.BLOCKED,
                        BuilderActionPreflight.Authorization.NOT_REQUIRED,
                        null, null, null, NOW), NOW, () -> {
                    calls.incrementAndGet();
                    return null;
                });
        assertEquals(BuilderActionPreflight.Decision.BLOCKED, result.decision);
        assertFalse(result.executed);
        assertEquals(0, calls.get());
    }

    @Test public void monitorOfferBoundaryRejectsStaleReadiness() throws Exception {
        BuilderActionPreflight.Execution<Void> result =
                BuilderActionBoundaries.offerMonitorRegistration(observation("OFFER_GITHUB_MONITOR_REGISTRATION",
                        BuilderActionPreflight.CapabilityState.AVAILABLE,
                        BuilderActionPreflight.Authorization.NOT_REQUIRED,
                        null, null, null, NOW.minusSeconds(121)), NOW);
        assertEquals(BuilderActionPreflight.Decision.RECHECK_REQUIRED, result.decision);
        assertFalse(result.executed);
    }

    @Test public void monitorSaveBoundaryDoesNotPersistWhenAuthorizationDenied() throws Exception {
        AtomicInteger writes = new AtomicInteger();
        BuilderActionPreflight.Execution<String> result =
                BuilderActionBoundaries.registerMonitor(observation("REGISTER_GITHUB_MONITOR",
                        BuilderActionPreflight.CapabilityState.AVAILABLE,
                        BuilderActionPreflight.Authorization.DENIED,
                        null, null, null, NOW), NOW, () -> {
                    writes.incrementAndGet();
                    return "saved";
                });
        assertEquals(BuilderActionPreflight.Decision.BLOCKED, result.decision);
        assertFalse(result.executed);
        assertEquals(0, writes.get());
    }

    @Test public void githubMonitorBoundaryDoesNotSendRequestWhenCapabilityUnknown() throws Exception {
        AtomicInteger requests = new AtomicInteger();
        BuilderActionPreflight.Execution<Void> result =
                BuilderActionBoundaries.checkGithubMonitor(observation("CHECK_GITHUB_ACTIONS",
                        BuilderActionPreflight.CapabilityState.UNKNOWN,
                        BuilderActionPreflight.Authorization.GRANTED,
                        null, null, null, NOW), NOW, () -> {
                    requests.incrementAndGet();
                    return null;
                });
        assertEquals(BuilderActionPreflight.Decision.RECHECK_REQUIRED, result.decision);
        assertFalse(result.executed);
        assertEquals(0, requests.get());
    }

    @Test public void githubMonitorBoundaryRunsRequestOnceWhenFreshAndAuthorized() throws Exception {
        AtomicInteger requests = new AtomicInteger();
        BuilderActionPreflight.Execution<Void> result =
                BuilderActionBoundaries.checkGithubMonitor(observation("CHECK_GITHUB_ACTIONS",
                        BuilderActionPreflight.CapabilityState.AVAILABLE,
                        BuilderActionPreflight.Authorization.GRANTED,
                        null, null, null, NOW), NOW, () -> {
                    requests.incrementAndGet();
                    return null;
                });
        assertEquals(BuilderActionPreflight.Decision.PROCEED, result.decision);
        assertTrue(result.executed);
        assertEquals(1, requests.get());
    }

    @Test public void actionBoundaryRejectsMismatchedActionIdentity() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        BuilderActionPreflight.Execution<Void> result =
                BuilderActionBoundaries.checkGithubMonitor(observation(
                        BuilderActionPreflight.CapabilityState.AVAILABLE,
                        BuilderActionPreflight.Authorization.GRANTED,
                        null, null, null, NOW), NOW, () -> {
                    calls.incrementAndGet();
                    return null;
                });
        assertEquals(BuilderActionPreflight.Decision.RECHECK_REQUIRED, result.decision);
        assertFalse(result.executed);
        assertEquals(0, calls.get());
    }
}
