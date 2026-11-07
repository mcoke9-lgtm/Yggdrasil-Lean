package org.yggdrasil.scheduler.builder;

import android.content.Context;
import org.json.JSONObject;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * Validates and stages the merged export emitted by BuilderRecoveryOverlayStore.
 * The export keeps its generation provenance while reusing the canonical snapshot
 * validators and isolated staging implementation.
 */
public final class BuilderRecoveryOverlayExportPackage {
    public static final String FORMAT = "yggdrasil-recovery-overlay-export";
    public static final int FORMAT_VERSION = 1;

    public static final class ValidationReport {
        public final String generationId;
        public final String conversationBaselineSha256;
        public final String subjectBaselineSha256;
        public final BuilderRecoveryPackage.ValidationReport recovery;

        private ValidationReport(String generationId, String conversationDigest,
                                 String subjectDigest,
                                 BuilderRecoveryPackage.ValidationReport recovery) {
            this.generationId = generationId;
            this.conversationBaselineSha256 = conversationDigest;
            this.subjectBaselineSha256 = subjectDigest;
            this.recovery = recovery;
        }
    }

    private BuilderRecoveryOverlayExportPackage() { }

    /** Validates outer provenance/checksum and both canonical nested snapshots. */
    public static ValidationReport validate(JSONObject root) {
        if (root == null) throw new IllegalArgumentException("Merged recovery export is required");
        try {
            if (!FORMAT.equals(root.getString("format"))
                    || root.getInt("format_version") != FORMAT_VERSION)
                throw new IllegalArgumentException("Unsupported merged recovery export format");
            String generation = root.getString("generation_id");
            if (!generation.matches("[A-Za-z0-9_-]{1,48}"))
                throw new IllegalArgumentException("Invalid recovery generation identity");
            String conversationDigest = digestField(root, "conversation_baseline_sha256");
            String subjectDigest = digestField(root, "subject_baseline_sha256");
            String claimed = root.getString("content_sha256");
            if (!claimed.matches("[0-9a-f]{64}"))
                throw new IllegalArgumentException("Invalid merged recovery export checksum");
            JSONObject content = new JSONObject(root.toString());
            content.remove("content_sha256");
            if (!claimed.equals(sha256(content.toString())))
                throw new IllegalArgumentException("Merged recovery export checksum mismatch");

            JSONObject canonical = canonicalPackage(root);
            BuilderRecoveryPackage.ValidationReport report = BuilderRecoveryPackage.validate(canonical);
            return new ValidationReport(generation, conversationDigest, subjectDigest, report);
        } catch (org.json.JSONException e) {
            throw new IllegalArgumentException("Malformed merged recovery export", e);
        }
    }

    /**
     * Stages a validated merged export into a fresh isolated database pair.
     * The staging ID is deliberately separate from the source generation ID.
     */
    public static BuilderRecoveryStager.StageResult stage(
            Context context, JSONObject root, String stagingId) {
        validate(root);
        return BuilderRecoveryStager.stage(context, canonicalPackage(root), stagingId);
    }

    private static JSONObject canonicalPackage(JSONObject root)
            throws org.json.JSONException {
        JSONObject canonical = new JSONObject();
        canonical.put("format", BuilderRecoveryPackage.FORMAT);
        canonical.put("format_version", BuilderRecoveryPackage.FORMAT_VERSION);
        canonical.put("created_at_epoch_millis", System.currentTimeMillis());
        canonical.put("conversation_snapshot", root.getJSONObject("conversation_snapshot"));
        canonical.put("subject_thread_snapshot", root.getJSONObject("subject_thread_snapshot"));
        canonical.put("content_sha256", sha256(canonical.toString()));
        return canonical;
    }

    private static String digestField(JSONObject root, String key)
            throws org.json.JSONException {
        String value = root.getString(key);
        if (!value.matches("[0-9a-f]{64}"))
            throw new IllegalArgumentException("Invalid " + key);
        return value;
    }

    private static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (byte b : digest)
                hex.append(String.format(java.util.Locale.ROOT, "%02x", b & 0xff));
            return hex.toString();
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }
}