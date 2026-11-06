package org.yggdrasil.scheduler.builder;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Adapter for OpenAI Chat Completions-compatible endpoints, including hosted
 * services and local servers that expose the same API shape.
 *
 * Credentials are supplied at call time by an injected source; they are never
 * persisted here. HTTP transport is injected so redirects, timeouts, and tests
 * remain under explicit control.
 */
public final class OpenAiCompatibleModelProvider implements BuilderModelProvider {
    public interface CredentialSource {
        /** Return a short-lived API token, or null for a local unauthenticated endpoint. */
        String readToken() throws Exception;
    }

    public interface HttpTransport {
        HttpResult post(String endpoint, Map<String, String> headers, String body,
                        int connectTimeoutMillis, int readTimeoutMillis) throws Exception;
    }

    public static final class HttpResult {
        public final int statusCode;
        public final String body;
        public HttpResult(int statusCode, String body) {
            this.statusCode = statusCode;
            this.body = body == null ? "" : body;
        }
    }

    private final String id;
    private final String endpoint;
    private final String model;
    private final CredentialSource credentials;
    private final HttpTransport transport;
    private final int connectTimeoutMillis;
    private final int readTimeoutMillis;

    public OpenAiCompatibleModelProvider(String id, String endpoint, String model,
            CredentialSource credentials, HttpTransport transport,
            int connectTimeoutMillis, int readTimeoutMillis) {
        if (id == null || id.trim().isEmpty()) throw new IllegalArgumentException("Provider ID is required");
        if (model == null || model.trim().isEmpty()) throw new IllegalArgumentException("Model ID is required");
        if (credentials == null || transport == null)
            throw new IllegalArgumentException("Credential source and HTTP transport are required");
        if (connectTimeoutMillis < 1000 || readTimeoutMillis < 1000)
            throw new IllegalArgumentException("HTTP timeouts must be at least one second");
        this.id = id.trim();
        this.endpoint = validateEndpoint(endpoint);
        this.model = model.trim();
        this.credentials = credentials;
        this.transport = transport;
        this.connectTimeoutMillis = connectTimeoutMillis;
        this.readTimeoutMillis = readTimeoutMillis;
    }

    @Override public String providerId() { return id; }

    @Override public Completion complete(Request request) throws Exception {
        if (request == null) throw new IllegalArgumentException("Model request is required");
        String token = credentials.readToken();
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Content-Type", "application/json; charset=utf-8");
        headers.put("Accept", "application/json");
        if (token != null && !token.trim().isEmpty())
            headers.put("Authorization", "Bearer " + token.trim());

        JSONObject payload = new JSONObject();
        JSONArray messages = new JSONArray();
        JSONObject system = new JSONObject();
        system.put("role", "system");
        system.put("content", request.systemInstruction);
        messages.put(system);
        JSONObject user = new JSONObject();
        user.put("role", "user");
        user.put("content", request.userMessage);
        messages.put(user);
        payload.put("model", model);
        payload.put("messages", messages);
        payload.put("max_tokens", request.maxOutputTokens);
        JSONObject responseFormat = new JSONObject();
        responseFormat.put("type", "json_object");
        payload.put("response_format", responseFormat);
        String requestBody = payload.toString();
        if (requestBody.length() > 1024 * 1024)
            throw new IllegalArgumentException("Serialized model request exceeds 1 MB limit");

        HttpResult result = transport.post(endpoint, headers, requestBody,
                connectTimeoutMillis, readTimeoutMillis);
        if (result == null) throw new IllegalStateException("Model transport returned no HTTP result");
        if (result.statusCode < 200 || result.statusCode >= 300)
            throw new IllegalStateException("Model endpoint returned HTTP " + result.statusCode);
        try {
            JSONObject response = new JSONObject(result.body);
            JSONArray choices = response.getJSONArray("choices");
            if (choices.length() == 0) throw new IllegalArgumentException("Model response has no choices");
            JSONObject message = choices.getJSONObject(0).getJSONObject("message");
            Object content = message.get("content");
            if (!(content instanceof String) || ((String) content).trim().isEmpty())
                throw new IllegalArgumentException("Model response content is not a non-empty string");
            String returnedModel = response.optString("model", model);
            return new Completion((String) content, returnedModel);
        } catch (JSONException malformed) {
            throw new IllegalArgumentException("Model endpoint returned an invalid chat-completion response", malformed);
        }
    }

    private static String validateEndpoint(String value) {
        if (value == null || value.trim().isEmpty())
            throw new IllegalArgumentException("Model endpoint is required");
        try {
            URI uri = URI.create(value.trim());
            String scheme = uri.getScheme();
            String host = uri.getHost();
            if (host == null || uri.getUserInfo() != null || uri.getFragment() != null
                    || uri.getQuery() != null || uri.getPath() == null || uri.getPath().isEmpty())
                throw new IllegalArgumentException("Model endpoint must be an absolute endpoint URL without user info, query, or fragment");
            boolean https = "https".equalsIgnoreCase(scheme);
            boolean loopbackHttp = "http".equalsIgnoreCase(scheme)
                    && "localhost".equalsIgnoreCase(host);
            if (!https && !loopbackHttp)
                throw new IllegalArgumentException("Use HTTPS, or HTTP only for localhost");
            return uri.toString();
        } catch (IllegalArgumentException invalid) {
            throw invalid;
        }
    }
}
