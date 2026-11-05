package org.yggdrasil.scheduler.builder;

import org.json.JSONObject;
import org.junit.Test;
import java.util.Map;
import static org.junit.Assert.*;

public class OpenAiCompatibleModelProviderTest {
    private static String validResponse() {
        try {
            JSONObject inner = new JSONObject();
            inner.put("body", "Ready.");
            inner.put("turn_type", "RESPONSE");
            inner.put("recipient", "USER");
            inner.put("reply_to_turn_id", JSONObject.NULL);
            inner.put("evidence_status", "PROPOSED");
            JSONObject message = new JSONObject().put("content", inner.toString());
            JSONObject choice = new JSONObject().put("message", message);
            return new JSONObject().put("model", "test-model")
                    .put("choices", new org.json.JSONArray().put(choice)).toString();
        } catch (org.json.JSONException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static final class FakeTransport implements OpenAiCompatibleModelProvider.HttpTransport {
        String endpoint;
        Map<String, String> headers;
        String requestBody;
        int connectTimeout;
        int readTimeout;
        OpenAiCompatibleModelProvider.HttpResult result =
                new OpenAiCompatibleModelProvider.HttpResult(200, validResponse());
        @Override public OpenAiCompatibleModelProvider.HttpResult post(
                String endpoint, Map<String, String> headers, String body, int connect, int read) {
            this.endpoint = endpoint;
            this.headers = headers;
            this.requestBody = body;
            this.connectTimeout = connect;
            this.readTimeout = read;
            return result;
        }
    }

    @Test public void sendsStructuredPromptAndParsesCompatibleCompletion() throws Exception {
        FakeTransport transport = new FakeTransport();
        OpenAiCompatibleModelProvider provider = new OpenAiCompatibleModelProvider(
                "hosted", "https://api.example.test/v1/chat/completions", "model-x",
                () -> "temporary-token", transport, 5000, 10000);
        BuilderModelProvider.Completion completion = provider.complete(
                new BuilderModelProvider.Request("system role", "{\"turns\":[]}", 2048));
        assertEquals("https://api.example.test/v1/chat/completions", transport.endpoint);
        assertEquals("Bearer temporary-token", transport.headers.get("Authorization"));
        assertEquals("application/json; charset=utf-8", transport.headers.get("Content-Type"));
        JSONObject payload = new JSONObject(transport.requestBody);
        assertEquals("model-x", payload.getString("model"));
        assertEquals(2048, payload.getInt("max_tokens"));
        assertEquals("json_object", payload.getJSONObject("response_format").getString("type"));
        assertEquals("system role", payload.getJSONArray("messages").getJSONObject(0).getString("content"));
        assertEquals("test-model", completion.modelId);
        assertTrue(completion.responseText.contains("Ready."));
        assertEquals(5000, transport.connectTimeout);
        assertEquals(10000, transport.readTimeout);
    }

    @Test public void allowsLocalLoopbackWithoutCredential() throws Exception {
        FakeTransport transport = new FakeTransport();
        OpenAiCompatibleModelProvider provider = new OpenAiCompatibleModelProvider(
                "local", "http://localhost:8080/v1/chat/completions", "local-model",
                () -> null, transport, 3000, 3000);
        provider.complete(new BuilderModelProvider.Request("system", "message", 128));
        assertFalse(transport.headers.containsKey("Authorization"));
    }

    @Test public void rejectsPlainHttpForNonLoopbackHosts() {
        assertThrows(IllegalArgumentException.class, () ->
                new OpenAiCompatibleModelProvider("local", "http://192.168.1.20:8080/v1/chat/completions",
                        "model", () -> null, new FakeTransport(), 3000, 3000));
    }

    @Test public void rejectsEndpointWithCredentialsOrQuery() {
        assertThrows(IllegalArgumentException.class, () ->
                new OpenAiCompatibleModelProvider("x", "https://user:pass@example.test/v1/chat",
                        "m", () -> null, new FakeTransport(), 3000, 3000));
        assertThrows(IllegalArgumentException.class, () ->
                new OpenAiCompatibleModelProvider("x", "https://example.test/v1/chat?key=secret",
                        "m", () -> null, new FakeTransport(), 3000, 3000));
    }

    @Test public void reportsHttpFailureWithoutExposingResponseBody() {
        FakeTransport transport = new FakeTransport();
        transport.result = new OpenAiCompatibleModelProvider.HttpResult(401, "sensitive provider error");
        OpenAiCompatibleModelProvider provider = new OpenAiCompatibleModelProvider(
                "hosted", "https://api.example.test/v1/chat/completions", "m",
                () -> "token", transport, 3000, 3000);
        try {
            provider.complete(new BuilderModelProvider.Request("system", "message", 100));
            fail("Expected HTTP failure");
        } catch (IllegalStateException expected) {
            assertEquals("Model endpoint returned HTTP 401", expected.getMessage());
            assertFalse(expected.getMessage().contains("sensitive"));
        } catch (Exception unexpected) {
            fail(unexpected.toString());
        }
    }

    @Test public void rejectsMalformedProviderResponse() {
        FakeTransport transport = new FakeTransport();
        transport.result = new OpenAiCompatibleModelProvider.HttpResult(200, "{not-json");
        OpenAiCompatibleModelProvider provider = new OpenAiCompatibleModelProvider(
                "local", "http://localhost:8080/v1/chat/completions", "m",
                () -> null, transport, 3000, 3000);
        assertThrows(IllegalArgumentException.class, () ->
                provider.complete(new BuilderModelProvider.Request("system", "message", 100)));
    }
}
