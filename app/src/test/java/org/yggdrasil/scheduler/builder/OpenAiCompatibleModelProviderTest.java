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

