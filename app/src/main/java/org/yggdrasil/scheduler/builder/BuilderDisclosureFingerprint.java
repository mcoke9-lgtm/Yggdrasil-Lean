package org.yggdrasil.scheduler.builder;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import java.util.List;

/** Unambiguous, deterministic fingerprint of the exact routes approved for disclosure. */
public final class BuilderDisclosureFingerprint {
    private BuilderDisclosureFingerprint() {}

    public static final class Route {
        public final String role;
        public final String providerId;
        public final String endpoint;
        public final String modelId;

        public Route(String role, String providerId, String endpoint, String modelId) {
            this.role = requireText(role, "Role");
            this.providerId = requireText(providerId, "Provider ID");
            this.endpoint = requireText(endpoint, "Endpoint");
            this.modelId = requireText(modelId, "Model ID");
        }
    }

    public static String create(List<Route> routes) {
        if (routes == null || routes.isEmpty())
            throw new IllegalArgumentException("At least one approved route is required");
        JSONArray encoded = new JSONArray();
        try {
            for (Route route : routes) {
                if (route == null) throw new IllegalArgumentException("Route must not be null");
                JSONObject item = new JSONObject();
                item.put("role", route.role);
                item.put("provider_id", route.providerId);
                item.put("endpoint", route.endpoint);
                item.put("model_id", route.modelId);
                encoded.put(item);
            }
            return encoded.toString();
        } catch (JSONException invalid) {
            throw new IllegalArgumentException("Could not encode route disclosure fingerprint", invalid);
        }
    }

    private static String requireText(String value, String label) {
        if (value == null || value.trim().isEmpty())
            throw new IllegalArgumentException(label + " is required");
        return value;
    }
}
