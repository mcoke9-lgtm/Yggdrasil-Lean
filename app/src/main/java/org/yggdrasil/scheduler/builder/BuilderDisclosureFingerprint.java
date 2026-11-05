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
            JSONObject approval = new JSONObject();
            approval.put("disclosure_version", "builder-prompt-scope-v2");
            approval.put("prompt_contents", new JSONArray()
                    .put("user_message")
                    .put("saved_thread_history")
                    .put("selected_role_instructions")
                    .put("timestamped_operational_limits_snapshot"));
            approval.put("routes", encoded);
            return approval.toString();
        } catch (JSONException invalid) {
            throw new IllegalArgumentException("Could not encode route disclosure fingerprint", invalid);
        }
    }


    /** Consent fingerprint for one scheduled run, isolated from pre-existing thread history. */
    public static String createOvernight(List<Route> routes, String taskPrompt) {
        if (routes == null || routes.size() != BuilderRole.values().length)
            throw new IllegalArgumentException("An overnight run requires all nine Builder routes");
        if (taskPrompt == null || taskPrompt.trim().isEmpty())
            throw new IllegalArgumentException("Overnight task prompt is required");
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
            JSONObject approval = new JSONObject();
            approval.put("disclosure_version", "builder-overnight-prompt-scope-v1");
            approval.put("prompt_contents", new JSONArray()
                    .put("scheduled_task_prompt")
                    .put("run_generated_thread_history")
                    .put("selected_role_instructions")
                    .put("timestamped_operational_limits_snapshot"));
            approval.put("task_prompt_sha256", sha256(taskPrompt.trim()));
            approval.put("routes", encoded);
            return approval.toString();
        } catch (JSONException invalid) {
            throw new IllegalArgumentException("Could not encode overnight disclosure fingerprint", invalid);
        }
    }

    public static String digest(String value) { return sha256(value); }

    private static String sha256(String value) {
        try {
            byte[] digest = java.security.MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (byte item : digest) hex.append(String.format(java.util.Locale.ROOT, "%02x", item & 0xff));
            return hex.toString();
        } catch (java.security.NoSuchAlgorithmException unavailable) {
            throw new IllegalStateException("SHA-256 is unavailable", unavailable);
        }
    }

    private static String requireText(String value, String label) {
        if (value == null || value.trim().isEmpty())
            throw new IllegalArgumentException(label + " is required");
        return value;
    }
}
