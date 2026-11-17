package org.yggdrasil.scheduler.intake;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;

/** Strict validator for YIM-001 work-intake queue version 2. This stages packets only; it does not place them in Roots. */
public final class IntakeQueueValidator {
    public static final String FORMAT = "YIM-001-Work-Intake-Queue";
    public static final int FORMAT_VERSION = 2;
    public static final int MAX_BYTES = 5 * 1024 * 1024;
    private static final Set<String> QUEUE_KEYS = set("format", "format_version", "queue_id", "packets");
    private static final Set<String> PACKET_KEYS = set("packet_id", "captured_at", "source_kind",
            "source_reference", "language", "content", "metadata", "placement_status",
            "primary_logical_location", "contextual_logical_locations",
            "source_content_preserved_as_submitted", "imported_record_id", "imported_version_id");

    private IntakeQueueValidator() {}

    public static ValidatedQueue validate(String raw) throws JSONException {
        if (raw == null || raw.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > MAX_BYTES)
            throw new IllegalArgumentException("Queue is empty or exceeds the 5 MB limit");
        JSONObject root = new JSONObject(raw);
        requireKeys(root, QUEUE_KEYS, "queue");
        if (!FORMAT.equals(root.getString("format")) || !(root.get("format_version") instanceof Integer)\n                || root.getInt("format_version") != FORMAT_VERSION)
            throw new IllegalArgumentException("Only YIM-001 work-intake queue version 2 is accepted");
        String queueId = nonBlank(root.getString("queue_id"), "queue_id");
        JSONArray packets = root.getJSONArray("packets");
        if (packets.length() > 1000) throw new IllegalArgumentException("Queue exceeds 1000 packet limit");
        Set<String> ids = new HashSet<>();
        int pending = 0;
        for (int i = 0; i < packets.length(); i++) {
            JSONObject p = packets.getJSONObject(i);
            requireKeys(p, PACKET_KEYS, "packet " + i);
            String id = nonBlank(p.getString("packet_id"), "packet_id");
            if (!ids.add(id)) throw new IllegalArgumentException("Duplicate packet ID: " + id);
            try { OffsetDateTime.parse(p.getString("captured_at")); }
            catch (Exception e) { throw new IllegalArgumentException("captured_at must be ISO-8601 with an offset: " + id); }
            nonBlank(p.getString("source_kind"), "source_kind");
            nonBlank(p.getString("language"), "language");
            Object sourceRef = p.get("source_reference");
            if (sourceRef != JSONObject.NULL && (!(sourceRef instanceof String) || ((String) sourceRef).trim().isEmpty()))
                throw new IllegalArgumentException("source_reference must be null or non-blank text: " + id);
            if (!Boolean.TRUE.equals(p.get("source_content_preserved_as_submitted")))
                throw new IllegalArgumentException("Source preservation marker must be true: " + id);
            String placement = p.getString("placement_status");
            if ("awaiting_placement".equals(placement)) {
                pending++;
                if (p.get("primary_logical_location") != JSONObject.NULL
                        || p.getJSONArray("contextual_logical_locations").length() != 0
                        || p.get("imported_record_id") != JSONObject.NULL
                        || p.get("imported_version_id") != JSONObject.NULL)
                    throw new IllegalArgumentException("Pending packet must not contain placement or import identity: " + id);
            } else if ("imported".equals(placement)) {
                nonBlank(p.getString("primary_logical_location"), "primary_logical_location");
                nonBlank(p.getString("imported_record_id"), "imported_record_id");
                nonBlank(p.getString("imported_version_id"), "imported_version_id");
                JSONArray contexts = p.getJSONArray("contextual_logical_locations");
                Set<String> contextIds = new HashSet<>();
                for (int j = 0; j < contexts.length(); j++) {
                    String node = nonBlank(contexts.getString(j), "contextual logical location");
                    if (!contextIds.add(node) || node.equals(p.getString("primary_logical_location")))
                        throw new IllegalArgumentException("Contextual locations must be unique and differ from primary: " + id);
                }
            } else throw new IllegalArgumentException("Unsupported placement_status: " + placement);
        }
        return new ValidatedQueue(queueId, packets.length(), pending, raw, root);
    }

    private static String nonBlank(String value, String field) {
        if (value == null || value.trim().isEmpty()) throw new IllegalArgumentException(field + " must be non-blank");
        return value;
    }

    private static void requireKeys(JSONObject object, Set<String> expected, String label) {
        Set<String> actual = new HashSet<>();
        Iterator<String> keys = object.keys();
        while (keys.hasNext()) actual.add(keys.next());
        if (!actual.equals(expected)) throw new IllegalArgumentException("Invalid " + label + " fields; expected " + expected);
    }

    private static Set<String> set(String... values) { return new HashSet<>(Arrays.asList(values)); }

    public static final class ValidatedQueue {
        public final String queueId, rawJson;
        public final int packetCount, pendingCount;
        private final JSONObject root;
        public JSONObject copyRoot() throws JSONException { return new JSONObject(rawJson); }
        ValidatedQueue(String queueId, int packetCount, int pendingCount, String rawJson, JSONObject root) {
            this.queueId=queueId; this.packetCount=packetCount; this.pendingCount=pendingCount;
            this.rawJson=rawJson; this.root=root;
        }
    }
}
