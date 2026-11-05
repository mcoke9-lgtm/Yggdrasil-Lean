package org.yggdrasil.scheduler.builder;

import org.json.JSONArray;
import org.json.JSONObject;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Iterator;
import java.util.Locale;

/** Combines conversation and operational provenance exports under one integrity checksum. */
public final class BuilderRecoveryExport {
    public static final int FORMAT_VERSION = 2;
    private BuilderRecoveryExport() {}

    public static JSONObject compose(JSONObject conversationSnapshot,
                                      JSONArray provenanceEvents,
                                      String provenanceStatus,
                                      String provenanceErrorClass) {
        if (conversationSnapshot == null) throw new IllegalArgumentException("Conversation snapshot is required");
        if (!"yggdrasil-nine-builder-conversations".equals(
                conversationSnapshot.optString("format", ""))) {
            throw new IllegalArgumentException("Unsupported conversation export format");
        }
        if (provenanceStatus == null || !("available".equals(provenanceStatus)
                || "empty".equals(provenanceStatus) || "unavailable".equals(provenanceStatus))) {
            throw new IllegalArgumentException("Invalid provenance status");
        }
        if (!"unavailable".equals(provenanceStatus) && provenanceEvents == null)
            throw new IllegalArgumentException("Provenance events are required when available");
        try {
            JSONObject root = new JSONObject(conversationSnapshot.toString());
            root.remove("content_sha256");
            root.put("format_version", FORMAT_VERSION);
            JSONObject provenance = new JSONObject();
            provenance.put("status", provenanceStatus);
            provenance.put("event_count", provenanceEvents == null ? JSONObject.NULL : provenanceEvents.length());
            JSONArray detachedEvents = provenanceEvents == null ? null : new JSONArray(provenanceEvents.toString());
            provenance.put("events", detachedEvents == null ? JSONObject.NULL : detachedEvents);
            provenance.put("error_class", provenanceErrorClass == null
                    ? JSONObject.NULL : provenanceErrorClass);
            root.put("operational_snapshot_provenance", provenance);
            root.put("content_sha256", sha256(root.toString()));
            return root;
        } catch (org.json.JSONException e) {
            throw new IllegalArgumentException("Could not compose recovery export", e);
        }
    }

    /** Verify checksum after removing the checksum field, matching export canonicalization. */
    public static boolean verifyChecksum(JSONObject export) {
        if (export == null || export.optInt("format_version", -1) != FORMAT_VERSION) return false;
        String expected = export.optString("content_sha256", "");
        if (expected.length() != 64) return false;
        try {
            JSONObject unsigned = new JSONObject(export.toString());
            unsigned.remove("content_sha256");
            return expected.equals(sha256(unsigned.toString()));
        } catch (Exception e) {
            return false;
        }
    }

    private static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (byte b : digest) hex.append(String.format(Locale.ROOT, "%02x", b & 0xff));
            return hex.toString();
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }
}
