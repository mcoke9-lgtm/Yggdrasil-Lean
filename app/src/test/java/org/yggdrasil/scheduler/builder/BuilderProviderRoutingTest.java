package org.yggdrasil.scheduler.builder;

import android.app.Application;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;
import java.util.List;
import java.util.UUID;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
public class BuilderProviderRoutingTest {
    private static final class FakeProvider implements BuilderModelProvider {
        final String id;
        Request lastRequest;
        String reportedModel = "fake-model";
        FakeProvider(String id) { this.id = id; }
        @Override public String providerId() { return id; }
        @Override public Completion complete(Request request) {
            lastRequest = request;
            assertNotNull(request.systemInstruction);
            assertNotNull(request.userMessage);
            return new Completion("{\"body\":\"A bounded finding.\","
                    + "\"turn_type\":\"RESPONSE\",\"recipient\":\"USER\","
                    + "\"reply_to_turn_id\":null,\"evidence_status\":\"PROPOSED\"}", reportedModel);
        }
    }

    @Test public void routesDifferentRolesToDifferentProviders() {
        FakeProvider hosted = new FakeProvider("hosted-api");
        FakeProvider local = new FakeProvider("local-model");
        BuilderProviderRegistry registry = new BuilderProviderRegistry(Arrays.asList(hosted, local));
        Map<BuilderRole, String> routes = new EnumMap<>(BuilderRole.class);
        routes.put(BuilderRole.HUGINN, "hosted-api");
        routes.put(BuilderRole.KVASIR, "local-model");
        BuilderProviderRouter router = new BuilderProviderRouter(registry, routes);
        assertSame(hosted, router.providerFor(BuilderRole.HUGINN));
        assertSame(local, router.providerFor(BuilderRole.KVASIR));
    }

    @Test public void refusesImplicitProviderFallback() {
        BuilderProviderRegistry registry = new BuilderProviderRegistry(
                Collections.<BuilderModelProvider>singletonList(new FakeProvider("hosted-api")));
        BuilderProviderRouter router = new BuilderProviderRouter(registry, Collections.emptyMap());
        assertThrows(IllegalStateException.class, () -> router.providerFor(BuilderRole.MARI));
    }

    @Test public void rejectsDuplicateProviderIds() {
        assertThrows(IllegalArgumentException.class, () -> new BuilderProviderRegistry(
                Arrays.asList(new FakeProvider("same"), new FakeProvider("same"))));
    }

    @Test public void modelResponderUsesProviderAndStrictParser() throws Exception {
        FakeProvider provider = new FakeProvider("vendor");
        provider.reportedModel = "inference";
        BuilderProviderRegistry registry = new BuilderProviderRegistry(
                Collections.<BuilderModelProvider>singletonList(provider));
        Map<BuilderRole, String> routes = new EnumMap<>(BuilderRole.class);
        routes.put(BuilderRole.HUGINN, "vendor");
        BuilderModelResponder responder = new BuilderModelResponder(
                new BuilderProviderRouter(registry, routes), 2048);
        BuilderConversationProtocol.Turn user = new BuilderConversationProtocol.Turn(
                BuilderConversationProtocol.CURRENT_VERSION, "provider-test", "turn-1",
                BuilderConversationProtocol.Participant.USER, null, null,
                BuilderConversationProtocol.TurnType.CONTRIBUTION,
                BuilderConversationProtocol.EvidenceStatus.UNKNOWN, 1L, "Review this.");
        BuilderConversationCoordinator.Reply reply = responder.respond(
                BuilderRole.HUGINN, Collections.singletonList(user), user);
        assertTrue(reply.body.contains("A bounded finding."));
        assertTrue(reply.body.startsWith("[MODEL_PROVENANCE role=HUGINN provider=vendor reported_model=inference]"));
        assertEquals(BuilderConversationProtocol.TurnType.RESPONSE, reply.type);
    }


    @Test public void provenanceSanitizesCarriageReturnAndLineFeedWithoutChangingOtherLetters() throws Exception {
        FakeProvider provider = new FakeProvider("ven\ndor");
        provider.reportedModel = "infer\rence";
        BuilderProviderRegistry registry = new BuilderProviderRegistry(
                Collections.<BuilderModelProvider>singletonList(provider));
        Map<BuilderRole, String> routes = new EnumMap<>(BuilderRole.class);
        routes.put(BuilderRole.HUGINN, "ven\ndor");
        BuilderModelResponder responder = new BuilderModelResponder(
                new BuilderProviderRouter(registry, routes), 2048);
        BuilderConversationProtocol.Turn user = new BuilderConversationProtocol.Turn(
                BuilderConversationProtocol.CURRENT_VERSION, "provenance-sanitize", "turn-1",
                BuilderConversationProtocol.Participant.USER, null, null,
                BuilderConversationProtocol.TurnType.CONTRIBUTION,
                BuilderConversationProtocol.EvidenceStatus.UNKNOWN, 1L, "Review this.");
        BuilderConversationCoordinator.Reply reply = responder.respond(
                BuilderRole.HUGINN, Collections.singletonList(user), user);
        assertTrue(reply.body.startsWith(
                "[MODEL_PROVENANCE role=HUGINN provider=ven_dor reported_model=infer_ence]"));
    }

    @Test public void laterBuilderReceivesAccumulatedPeerContext() throws Exception {
        FakeProvider provider = new FakeProvider("local-model");
        BuilderProviderRegistry registry = new BuilderProviderRegistry(
                Collections.<BuilderModelProvider>singletonList(provider));
        Map<BuilderRole, String> routes = new EnumMap<>(BuilderRole.class);
        routes.put(BuilderRole.KVASIR, "local-model");
        BuilderModelResponder responder = new BuilderModelResponder(
                new BuilderProviderRouter(registry, routes), 2048);
        BuilderConversationProtocol.Turn user = new BuilderConversationProtocol.Turn(
                BuilderConversationProtocol.CURRENT_VERSION, "peer-context", "turn-1",
                BuilderConversationProtocol.Participant.USER, null, null,
                BuilderConversationProtocol.TurnType.CONTRIBUTION,
                BuilderConversationProtocol.EvidenceStatus.UNKNOWN, 1L, "Research this.");
        BuilderConversationProtocol.Turn peer = new BuilderConversationProtocol.Turn(
                BuilderConversationProtocol.CURRENT_VERSION, "peer-context", "turn-2",
                BuilderConversationProtocol.Participant.HUGINN,
                BuilderConversationProtocol.Participant.USER, "turn-1",
                BuilderConversationProtocol.TurnType.RESPONSE,
                BuilderConversationProtocol.EvidenceStatus.PROPOSED, 2L, "Earlier peer finding.");
        responder.respond(BuilderRole.KVASIR, Arrays.asList(user, peer), user);
        assertTrue(provider.lastRequest.userMessage.contains("turn-2"));
        assertTrue(provider.lastRequest.userMessage.contains("Earlier peer finding."));
    }

    @Test public void rejectsInvalidOutputTokenLimits() {
        assertThrows(IllegalArgumentException.class, () -> new BuilderModelResponder(null, 2048));
        assertThrows(IllegalArgumentException.class, () ->
                new BuilderModelProvider.Request("system", "message", 0));
        assertThrows(IllegalArgumentException.class, () ->
                new BuilderModelProvider.Request("system", "message", 32769));
    }

    @Test public void fullNineRoleRoundCompletesThroughOneProvider() {
        Application app = RuntimeEnvironment.getApplication();
        String databaseName = "builder-round-" + UUID.randomUUID() + ".db";
        BuilderConversationStore store = new BuilderConversationStore(app, databaseName);
        try {
            FakeProvider provider = new FakeProvider("shared-provider");
            BuilderProviderRegistry registry = new BuilderProviderRegistry(
                    Collections.<BuilderModelProvider>singletonList(provider));
            Map<BuilderRole, String> routes = new EnumMap<>(BuilderRole.class);
            for (BuilderRole role : BuilderRole.values()) routes.put(role, "shared-provider");
            BuilderModelResponder responder = new BuilderModelResponder(
                    new BuilderProviderRouter(registry, routes), 2048);
            BuilderConversationCoordinator coordinator =
                    new BuilderConversationCoordinator(store, responder);
            BuilderConversationCoordinator.Result result = coordinator.submit(
                    "all-nine-thread", "All Builders, review this project.", Arrays.asList(BuilderRole.values()));
            assertEquals(10, result.appendedTurns.size());
            assertTrue(result.failedRoles.isEmpty());
            assertTrue(result.unroutedRecipients.isEmpty());
            List<BuilderConversationProtocol.Turn> turns = store.readThread("all-nine-thread");
            assertEquals(10, turns.size());
            for (BuilderRole role : BuilderRole.values()) {
                boolean found = false;
                for (BuilderConversationProtocol.Turn turn : turns)
                    if (turn.speaker.name().equals(role.name())) found = true;
                assertTrue("Missing Builder role " + role, found);
            }
        } finally {
            store.close();
            app.deleteDatabase(databaseName);
        }
    }
}
