package org.yggdrasil.scheduler.builder;

import static org.junit.Assert.*;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

public class BuilderRecoveryIntegrityTest {
    private static final String DIGEST = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb";

    private JSONObject conversation() throws Exception {
        return new JSONObject().put("format", "yggdrasil-nine-builder-conversations")
                .put("format_version", 1).put("thread_count", 1).put("turn_count", 1)
                .put("threads", new JSONArray().put(new JSONObject().put("thread_id", "thread-a")
                        .put("turns", new JSONArray().put(new JSONObject().put("turn_id", "turn-a")))))
                .put("content_sha256", "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa");
    }

    private JSONObject event(String id) throws Exception {
        return new JSONObject().put("event_id", id).put("thread_id", "thread-a")
                .put("round_started_at_epoch_ms", 1793900000000L)
                .put("register_schema", "yggdrasil-operational-limits")
                .put("register_schema_version", "1.0.0").put("register_status", "available")
                .put("snapshot_sha256", DIGEST).put("source", "bundled_asset:operational-limits.json")
                .put("evaluated_on", "2026-11-05")
                .put("selected_builders", new JSONArray().put("HUGINN"))
                .put("evidence_refs", new JSONArray());
    }

    private JSONObject export(JSONArray events, String status) throws Exception {
        return BuilderRecoveryExport.compose(conversation(), events, status, null);
    }

    @Test public void validatesCompleteExportAndReconcilesOriginalEvents() throws Exception {
        JSONArray source = new JSONArray().put(event("event-1"));
        JSONObject backup = export(source, "available");
        BuilderRecoveryIntegrity.Report report = BuilderRecoveryIntegrity.validate(backup);
        assertTrue(report.errors.toString(), report.valid);
        assertTrue(report.checksumValid);
        assertTrue(report.provenanceAvailable);
        assertTrue(BuilderRecoveryIntegrity.reconcileProvenance(source, backup));
    }

    @Test public void detectsChangedProvenanceEvents() throws Exception {
        JSONArray source = new JSONArray().put(event("event-1")).put(event("event-2"));
        JSONObject backup = export(source, "available");
        JSONArray altered = backup.getJSONObject("operational_snapshot_provenance").getJSONArray("events");
        altered.getJSONObject(0).put("thread_id", "other-thread");
        assertEquals("thread-a", source.getJSONObject(0).getString("thread_id"));
        assertFalse(BuilderRecoveryIntegrity.reconcileProvenance(source, backup));
        assertFalse(BuilderRecoveryIntegrity.validate(backup).valid);
    }

    @Test public void detectsReorderedProvenanceEvents() throws Exception {
        JSONArray source = new JSONArray().put(event("event-1")).put(event("event-2"));
        JSONObject backup = export(source, "available");
        JSONArray reorderedSource = new JSONArray().put(event("event-2")).put(event("event-1"));
        assertFalse(BuilderRecoveryIntegrity.reconcileProvenance(reorderedSource, backup));
    }

    @Test public void rejectsDuplicateIdsAndMalformedSnapshotDigests() throws Exception {
        JSONArray events = new JSONArray().put(event("duplicate")).put(event("duplicate"));
        events.getJSONObject(1).put("snapshot_sha256", "not-a-digest");
        JSONObject backup = export(events, "available");
        BuilderRecoveryIntegrity.Report report = BuilderRecoveryIntegrity.validate(backup);
        assertFalse(report.valid);
        assertTrue(report.errors.contains("provenance_event_id_duplicate:1"));
        assertTrue(report.errors.contains("provenance_snapshot_digest_invalid:1"));
    }

    @Test public void rejectsConversationCountMismatch() throws Exception {
        JSONObject backup = export(new JSONArray(), "empty");
        backup.put("turn_count", 99);
        backup.put("content_sha256", ""); // also makes checksum invalid
        BuilderRecoveryIntegrity.Report report = BuilderRecoveryIntegrity.validate(backup);
        assertFalse(report.valid);
        assertTrue(report.errors.contains("turn_count_mismatch"));
        assertTrue(report.errors.contains("checksum_invalid"));
    }

    @Test public void acceptsExplicitlyUnavailableJournalButDoesNotClaimReconciliation() throws Exception {
        JSONObject backup = BuilderRecoveryExport.compose(conversation(), null,
                "unavailable", "SQLiteException");
        BuilderRecoveryIntegrity.Report report = BuilderRecoveryIntegrity.validate(backup);
        assertTrue(report.errors.toString(), report.valid);
        assertFalse(report.provenanceAvailable);
        assertFalse(BuilderRecoveryIntegrity.reconcileProvenance(new JSONArray(), backup));
    }

    @Test public void detectsTamperingWithChecksumProtectedContent() throws Exception {
        JSONObject backup = export(new JSONArray(), "empty");
        assertTrue(BuilderRecoveryIntegrity.validate(backup).valid);
        backup.getJSONArray("threads").getJSONObject(0).put("thread_id", "tampered");
        assertFalse(BuilderRecoveryIntegrity.validate(backup).valid);
    }
}
