package org.yggdrasil.scheduler.builder;

import android.content.Context;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Loads the shared, timestamped operational limits snapshot and evaluates its freshness.
 * This is a local snapshot, not live account/provider telemetry.
 */
public final class BuilderOperationalLimits {
    public static final int DEFAULT_MAX_AGE_DAYS = 7;
    private static final int MAX_REGISTER_BYTES = 256 * 1024;

    public static final class Snapshot {
        private final JSONObject json;
        private Snapshot(JSONObject json) { this.json = json; }
        public String toPromptJson() {
            try { return json.toString(2); }
            catch (JSONException impossible) { return "{\"register_status\":\"UNAVAILABLE\"}"; }
        }
    }

    private BuilderOperationalLimits() {}

    public static Snapshot load(Context context) {
        if (context == null) return unavailable("Application context unavailable");
        try (InputStream input = context.getAssets().open("operational-limits.json");
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int total = 0;
            int count;
            while ((count = input.read(buffer)) != -1) {
                total += count;
                if (total > MAX_REGISTER_BYTES)
                    return unavailable("Operational limits register exceeds size limit");
                output.write(buffer, 0, count);
            }
            return fromJson(new String(output.toByteArray(), StandardCharsets.UTF_8), LocalDate.now());
        } catch (Exception failure) {
            return unavailable("Operational limits register could not be loaded: "
                    + failure.getClass().getSimpleName());
        }
    }

    /** Pure evaluator for deterministic tests and other hosts. */
    public static Snapshot fromJson(String source, LocalDate today) {
        if (today == null) return unavailable("Evaluation date unavailable");
        try {
            JSONObject register = new JSONObject(source);
            if (!"yggdrasil.operational-capacity-register".equals(register.optString("schema"))
                    || !"1.0.0".equals(register.optString("schema_version")))
                return unavailable("Unsupported operational limits register schema");

            JSONArray sourceConstraints = register.optJSONArray("constraints");
            if (sourceConstraints == null) return unavailable("Constraints collection is missing");
            JSONArray evaluated = new JSONArray();
            Map<String, JSONObject> seenCapabilities = new HashMap<>();
            for (int i = 0; i < sourceConstraints.length(); i++) {
                JSONObject entry = new JSONObject(sourceConstraints.getJSONObject(i).toString());
                evaluateEntry(entry, today);
                String capability = entry.optString("capability", "").trim()
                        .toLowerCase(Locale.ROOT);
                if (capability.isEmpty()) {
                    entry.put("gate_decision", "RECHECK_REQUIRED");
                    entry.put("gate_reason", "Capability name is missing; the record cannot be matched safely.");
                } else if (seenCapabilities.containsKey(capability)) {
                    JSONObject first = seenCapabilities.get(capability);
                    first.put("gate_decision", "RECHECK_REQUIRED");
                    first.put("gate_reason", "Duplicate capability records exist; reconcile them before relying on either record.");
                    entry.put("gate_decision", "RECHECK_REQUIRED");
                    entry.put("gate_reason", "Duplicate capability records exist; reconcile them before relying on either record.");
                } else {
                    seenCapabilities.put(capability, entry);
                }
                evaluated.put(entry);
            }
            register.put("constraints", evaluated);
            JSONObject evaluation = new JSONObject();
            evaluation.put("evaluated_on", today.toString());
            evaluation.put("maximum_evidence_age_days", DEFAULT_MAX_AGE_DAYS);
            evaluation.put("live_telemetry", false);
            evaluation.put("unlisted_capability_decision", "RECHECK_REQUIRED");
            evaluation.put("rule", "A capability absent from this snapshot is unknown, never implicitly available.");
            register.put("runtime_evaluation", evaluation);
            register.put("register_status", "LOADED");
            return new Snapshot(register);
        } catch (Exception malformed) {
            return unavailable("Operational limits register is malformed: "
                    + malformed.getClass().getSimpleName());
        }
    }

    private static void evaluateEntry(JSONObject entry, LocalDate today) throws JSONException {
        String state = entry.optString("state", "unknown").toLowerCase(java.util.Locale.ROOT);
        String observed = entry.optString("observed_at", "");
        String decision = "RECHECK_REQUIRED";
        String reason = "State or evidence is not sufficient to rely on.";

        try {
            LocalDate observedDate = LocalDate.parse(observed);
            long age = ChronoUnit.DAYS.between(observedDate, today);
            entry.put("evidence_age_days", age);
            if (age < 0) {
                reason = "Observation date is in the future; verify the device clock and source record.";
            } else if (age > DEFAULT_MAX_AGE_DAYS) {
                reason = "Evidence is stale (" + age + " days old); refresh from the authoritative source.";
            } else if (!capacityIsConsistent(entry)) {
                reason = "Capacity figures are missing or inconsistent; reconcile maximum, usage, and remaining.";
            } else if ("blocked".equals(state)) {
                decision = "BLOCKED";
                reason = "Fresh register evidence records this capability as blocked.";
            } else if ("constrained".equals(state)) {
                decision = "PROCEED_WITH_CONSTRAINTS";
                reason = "Proceed only within the recorded constraints and safe alternative.";
            } else if ("available".equals(state)) {
                decision = "PROCEED";
                reason = "Fresh register evidence records this capability as available.";
            } else {
                reason = "Register state is " + state + "; verify current availability.";
            }
        } catch (Exception invalidDate) {
            entry.put("evidence_age_days", JSONObject.NULL);
            reason = "Observation date is missing or invalid; refresh before relying on this record.";
        }
        entry.put("gate_decision", decision);
        entry.put("gate_reason", reason);
    }

    private static boolean capacityIsConsistent(JSONObject entry) {
        Object maximum = entry.opt("maximum_capacity");
        Object usage = entry.opt("current_usage");
        Object remaining = entry.opt("remaining_capacity");
        boolean maxMissing = maximum == null || maximum == JSONObject.NULL;
        boolean usageMissing = usage == null || usage == JSONObject.NULL;
        boolean remainingMissing = remaining == null || remaining == JSONObject.NULL;
        if (maxMissing && usageMissing && remainingMissing)
            return true; // Capacity is optional for non-capacity constraints.
        if (maxMissing || usageMissing || remainingMissing) return false;
        if (!(maximum instanceof Number) || !(usage instanceof Number) || !(remaining instanceof Number))
            return false;
        double max = ((Number) maximum).doubleValue();
        double used = ((Number) usage).doubleValue();
        double left = ((Number) remaining).doubleValue();
        return max >= 0 && used >= 0 && left >= 0 && used <= max
                && Math.abs((max - used) - left) < 0.000001;
    }

    private static Snapshot unavailable(String reason) {
        JSONObject result = new JSONObject();
        try {
            result.put("schema", "yggdrasil.operational-capacity-runtime-snapshot");
            result.put("register_status", "UNAVAILABLE");
            result.put("live_telemetry", false);
            result.put("unlisted_capability_decision", "RECHECK_REQUIRED");
            result.put("reason", reason);
            result.put("constraints", new JSONArray());
        } catch (JSONException impossible) {
            // JSONObject.put only throws for a null key; keys above are constants.
        }
        return new Snapshot(result);
    }
}
