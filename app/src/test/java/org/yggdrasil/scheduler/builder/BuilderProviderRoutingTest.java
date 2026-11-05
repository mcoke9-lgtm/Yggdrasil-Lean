package org.yggdrasil.scheduler.builder;

import org.junit.Test;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;
import static org.junit.Assert.*;

public class BuilderProviderRoutingTest {
    private static final class FakeProvider implements BuilderModelProvider {
        final String id;
        FakeProvider(String id) { this.id = id; }
        @Override public String providerId() { return id; }
        @Override public Completion complete(Request request) {
            assertNotNull(request.systemInstruction);
            assertNotNull(request.userMessage);
            return new Completion("{\"body\":\"A bounded finding.\","
                    + "\"turn_type\":\"RESPONSE\",\"recipient\":\"USER\","
                    + "\"reply_to_turn_id\":null,\"evidence_status\":\"PROPOSED\"}", "fake-model");
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
        FakeProvider provider = new FakeProvider("local-model");
        BuilderProviderRegistry registry = new BuilderProviderRegistry(
                Collections.<BuilderModelProvider>singletonList(provider));
        Map<BuilderRole, String> routes = new EnumMap<>(BuilderRole.class);
        routes.put(BuilderRole.HUGINN, "local-model");
        BuilderModelResponder responder = new BuilderModelResponder(
                new BuilderProviderRouter(registry, routes), 2048);
        BuilderConversationProtocol.Turn user = new BuilderConversationProtocol.Turn(
                BuilderConversationProtocol.CURRENT_VERSION, "provider-test", "turn-1",
                BuilderConversationProtocol.Participant.USER, null, null,
                BuilderConversationProtocol.TurnType.CONTRIBUTION,
                BuilderConversationProtocol.EvidenceStatus.UNKNOWN, 1L, "Review this.");
        BuilderConversationCoordinator.Reply reply = responder.respond(
                BuilderRole.HUGINN, Collections.singletonList(user), user);
        assertEquals("A bounded finding.", reply.body);
        assertEquals(BuilderConversationProtocol.TurnType.RESPONSE, reply.type);
    }

    @Test public void rejectsInvalidOutputTokenLimits() {
        assertThrows(IllegalArgumentException.class, () -> new BuilderModelResponder(null, 2048));
        assertThrows(IllegalArgumentException.class, () ->
                new BuilderModelProvider.Request("system", "message", 0));
        assertThrows(IllegalArgumentException.class, () ->
                new BuilderModelProvider.Request("system", "message", 32769));
    }
}
