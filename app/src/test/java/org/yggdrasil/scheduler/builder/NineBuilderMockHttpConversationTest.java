package org.yggdrasil.scheduler.builder;

import android.app.Application;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.Assert.*;

/**
 * Exercises all nine role prompts through the real HTTP adapter's request/response
 * serialization, while replacing only the network transport with a deterministic
 * in-memory mock. No network request or API credential is used.
 */
@RunWith(RobolectricTestRunner.class)
public class NineBuilderMockHttpConversationTest {
    private static final class MockHttpTransport implements OpenAiCompatibleModelProvider.HttpTransport {
        final List<String> requestBodies = new ArrayList<>();
        final List<Map<String, String>> requestHeaders = new ArrayList<>();

        @Override public OpenAiCompatibleModelProvider.HttpResult post(
                String endpoint, Map<String, String> headers, String body,
                int connectTimeoutMillis, int readTimeoutMillis) throws Exception {
            assertEquals("http://localhost:12345/v1/chat/completions", endpoint);
            assertTrue(connectTimeoutMillis >= 1000);
            assertTrue(readTimeoutMillis >= 1000);
            requestBodies.add(body);
            requestHeaders.add(headers);
            int number = requestBodies.size();

            JSONObject builderReply = new JSONObject();
            builderReply.put("body", "Mock HTTP exchange " + number + ".");
            builderReply.put("turn_type", "RESPONSE");
            builderReply.put("recipient", "USER");
            builderReply.put("reply_to_turn_id", JSONObject.NULL);
            builderReply.put("evidence_status", "PROPOSED");

            JSONObject message = new JSONObject();
            message.put("content", builderReply.toString());
            JSONObject choice = new JSONObject();
            choice.put("message", message);
            JSONArray choices = new JSONArray();
            choices.put(choice);
            JSONObject response = new JSONObject();
            response.put("model", "mock-chat");
            response.put("choices", choices);
            return new OpenAiCompatibleModelProvider.HttpResult(200, response.toString());
        }
    }

    @Test public void allNineBuildersCompleteThroughMockHttpAdapterWithAccumulatedContext()
            throws Exception {
        Application app = RuntimeEnvironment.getApplication();
        String databaseName = "nine-builder-http-" + UUID.randomUUID() + ".db";
        BuilderConversationStore store = new BuilderConversationStore(app, databaseName);
        try {
            MockHttpTransport transport = new MockHttpTransport();
            OpenAiCompatibleModelProvider provider = new OpenAiCompatibleModelProvider(
                    "mock-local",
                    "http://localhost:12345/v1/chat/completions",
                    "mock-chat",
                    () -> null,
                    transport,
                    3000,
                    5000);
            BuilderProviderRegistry registry = new BuilderProviderRegistry(
                    Collections.<BuilderModelProvider>singletonList(provider));
            Map<BuilderRole, String> routes = new EnumMap<>(BuilderRole.class);
            for (BuilderRole role : BuilderRole.values()) routes.put(role, "mock-local");
            BuilderModelResponder responder = new BuilderModelResponder(
                    new BuilderProviderRouter(registry, routes), 1024);
            BuilderConversationCoordinator coordinator =
                    new BuilderConversationCoordinator(store, responder);

            BuilderConversationCoordinator.Result result = coordinator.submit(
                    "nine-role-mock-http",
                    "Review the Nine Builder conversation runtime and identify what is verified.",
                    Arrays.asList(BuilderRole.values()));

            assertEquals(10, result.appendedTurns.size());
            assertTrue(result.failedRoles.toString(), result.failedRoles.isEmpty());
            assertTrue(result.unroutedRecipients.toString(), result.unroutedRecipients.isEmpty());
            assertEquals(9, transport.requestBodies.size());
            assertEquals(9, transport.requestHeaders.size());

            List<BuilderConversationProtocol.Turn> saved = store.readThread("nine-role-mock-http");
            assertEquals(10, saved.size());
            assertEquals(BuilderConversationProtocol.Participant.USER, saved.get(0).speaker);

            for (int i = 0; i < BuilderRole.values().length; i++) {
                BuilderRole role = BuilderRole.values()[i];
                JSONObject request = new JSONObject(transport.requestBodies.get(i));
                JSONArray messages = request.getJSONArray("messages");
                String systemInstruction = messages.getJSONObject(0).getString("content");
                assertTrue("Role-specific prompt missing " + role.getDisplayName(),
                        systemInstruction.contains("You are the " + role.getDisplayName() + " role"));
                assertEquals("mock-chat", request.getString("model"));
                assertFalse("Mock endpoint must not receive an Authorization header",
                        transport.requestHeaders.get(i).containsKey("Authorization"));

                String userMessage = messages.getJSONObject(1).getString("content");
                JSONObject userPayload = new JSONObject(userMessage);
                JSONArray context = userPayload.getJSONArray("turns_oldest_first");
                assertEquals("Each Builder must see all earlier persisted turns", i + 1, context.length());
                if (i > 0) {
                    assertTrue(userMessage.contains("Mock HTTP exchange " + i + "."));
                }
            }

            for (int i = 0; i < BuilderRole.values().length; i++) {
                BuilderRole expected = BuilderRole.values()[i];
                BuilderConversationProtocol.Turn actual = saved.get(i + 1);
                assertEquals(expected.name(), actual.speaker.name());
                assertEquals(BuilderConversationProtocol.TurnType.RESPONSE, actual.type);
                assertEquals("Mock HTTP exchange " + (i + 1) + ".", actual.body);
            }
        } finally {
            store.close();
            app.deleteDatabase(databaseName);
        }
    }
}
