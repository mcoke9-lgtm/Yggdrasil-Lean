package org.yggdrasil.scheduler.builder;

import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Structural and provenance integrity checks for recovery format v2.
 * This validates exported evidence; it does not authenticate its author or origin.
 */
public final class BuilderRecoveryIntegrity {
    private static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");

    private BuilderRecoveryIntegrity() {}

    public static final class Report {
        public final boolean valid;
        public final boolean checksumValid;
        public final boolean provenanceAvailable;
        public final List<String> errors;

        private Report(boolean checksumValid, boolean provenanceAvailable, List<String> errors) {
            this.checksumValid = checksumValid;
            this.provenanceAvailable = provenanceAvailable;
            this.errors = java.util.Collections.unmodifiableList(new ArrayList<>(errors));
            this.valid = errors.isEmpty();
        }
    }

    public static Report validate(JSONObject export) {
        List<String> errors = new ArrayList<>();
        if (export == null) {
            errors.add("export_missing");
            return new Report(false, false, errors);
        }
        if (!"yggdrasil-nine-builder-conversations".equals(export.optString("format", "")))
            errors.add("unsupported_conversation_format");
        if (export.optInt("format_version", -1) != BuilderRecoveryExport.FORMAT_VERSION)
            errors.add("unsupported_recovery_format_version");

        JSONArray threads = export.optJSONArray("threads");
        if (threads == null) {
            errors.add("threads_missing_or_invalid");
        } else {
            Set<String> threadIds = new HashSet<>();
            int observedTurns = 0;
            for (int i = 0; i < threads.length(); i++) {
                JSONObject thread = threads.optJSONObject(i);
                if (thread == null) {
                    errors.add("thread_entry_invalid:" + i);
                    continue;
                }
                String threadId = thread.optString("thread_id", "").trim();
                if (threadId.isEmpty()) errors.add("thread_id_missing:" + i);
                else if (!threadIds.add(threadId)) errors.add("thread_id_duplicate:" + i);
                JSONArray turns = thread.optJSONArray("turns");
                if (turns == null) errors.add("thread_turns_missing:" + i);
                else observedTurns += turns.length();
            }
            if (export.optInt("thread_count", -1) != threads.length())
                errors.add("thread_count_mismatch");
            if (export.optInt("turn_count", -1) != observedTurns)
                errors.add("turn_count_mismatch");
        }

        JSONObject provenance = export.optJSONObject("operational_snapshot_provenance");
        boolean available = false;
        if (provenance == null) {
            errors.add("provenance_section_missing_or_invalid");
        } else {
            String status = provenance.optString("status", "");
            JSONArray events = provenance.optJSONArray("events");
            boolean eventCountNull = provenance.isNull("event_count")
                    || !provenance.has("event_count");
            if ("available".equals(status) || "empty".equals(status)) {
                available = true;
                if (events == null) errors.add("provenance_events_missing");
                if (eventCountNull) errors.add("provenance_event_count_missing");
                else if (events != null && provenance.optInt("event_count", -1) != events.length())
                    errors.add("provenance_event_count_mismatch");
                if ("available".equals(status) && events != null && events.length() == 0)
                    errors.add("available_provenance_is_empty");
                if ("empty".equals(status) && events != null && events.length() != 0)
                    errors.add("empty_provenance_contains_events");
                if (!provenance.isNull("error_class"))
                    errors.add("unexpected_provenance_error_class");
                if (events != null) validateEvents(events, errors);
            } else if ("unavailable".equals(status)) {
                if (!eventCountNull) errors.add("unavailable_event_count_must_be_null");
                if (!provenance.isNull("events")) errors.add("unavailable_events_must_be_null");
                if (provenance.optString("error_class", "").trim().isEmpty())
                    errors.add("unavailable_error_class_missing");
            } else {
                errors.add("invalid_provenance_status");
            }
        }

        boolean checksumValid = BuilderRecoveryExport.verifyChecksum(export);
        if (!checksumValid) errors.add("checksum_invalid");
        return new Report(checksumValid, available, errors);
    }

    /**
     * Confirms that every source journal event survived export without alteration or reordering.
     * The expected events must come from the independently retained source capture.
     */
    public static boolean reconcileProvenance(JSONArray expectedEvents, JSONObject export) {
        if (expectedEvents == null || export == null) return false;
        JSONObject provenance = export.optJSONObject("operational_snapshot_provenance");
        if (provenance == null) return false;
        String status = provenance.optString("status", "");
        if (!"available".equals(status) && !"empty".equals(status)) return false;
        JSONArray actualEvents = provenance.optJSONArray("events");
        return actualEvents != null && jsonEquals(expectedEvents, actualEvents);
    }

    private static void validateEvents(JSONArray events, List<String> errors) {
        Set<String> eventIds = new HashSet<>();
        for (int i = 0; i < events.length(); i++) {
            JSONObject event = events.optJSONObject(i);
            if (event == null) {
                errors.add("provenance_event_invalid:" + i);
                continue;
            }
            String id = event.optString("event_id", "").trim();
            if (id.isEmpty()) errors.add("provenance_event_id_missing:" + i);
            else if (!eventIds.add(id)) errors.add("provenance_event_id_duplicate:" + i);
            requireText(event, "thread_id", "provenance_thread_id_missing:" + i, errors);
            if (event.optLong("round_started_at_epoch_ms", 0) <= 0)
                errors.add("provenance_timestamp_invalid:" + i);
            requireText(event, "register_schema", "provenance_schema_missing:" + i, errors);
            requireText(event, "register_schema_version", "provenance_schema_version_missing:" + i, errors);
            requireText(event, "register_status", "provenance_register_status_missing:" + i, errors);
            String digest = event.optString("snapshot_sha256", "");
            if (!SHA256.matcher(digest).matches()) errors.add("provenance_snapshot_digest_invalid:" + i);
            requireText(event, "source", "provenance_source_missing:" + i, errors);
            if (event.optJSONArray("selected_builders") == null
                    || event.optJSONArray("selected_builders").length() == 0)
                errors.add("provenance_selected_builders_missing:" + i);
            if (event.optJSONArray("evidence_refs") == null)
                errors.add("provenance_evidence_refs_missing:" + i);
        }
    }

    private static void requireText(JSONObject object, String key, String error, List<String> errors) {
        if (object.optString(key, "").trim().isEmpty()) errors.add(error);
    }

    private static boolean jsonEquals(Object left, Object right) {
        if (left == right) return true;
        if (left == null || right == null) return false;
        if (left instanceof JSONObject && right instanceof JSONObject) {
            JSONObject a = (JSONObject) left;
            JSONObject b = (JSONObject) right;
            if (a.length() != b.length()) return false;
            Iterator<String> keys = a.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                if (!b.has(key) || !jsonEquals(a.opt(key), b.opt(key))) return false;
            }
            return true;
        }
        if (left instanceof JSONArray && right instanceof JSONArray) {
            JSONArray a = (JSONArray) left;
            JSONArray b = (JSONArray) right;
            if (a.length() != b.length()) return false;
            for (int i = 0; i < a.length(); i++)
                if (!jsonEquals(a.opt(i), b.opt(i))) return false;
            return true;
        }
        if (left instanceof Number && right instanceof Number)
            return Double.compare(((Number) left).doubleValue(), ((Number) right).doubleValue()) == 0;
        return left.equals(right);
    }
}
