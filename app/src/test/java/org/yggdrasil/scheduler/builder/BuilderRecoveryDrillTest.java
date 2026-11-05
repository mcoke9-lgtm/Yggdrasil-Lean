package org.yggdrasil.scheduler.builder;

import static org.junit.Assert.*;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

public class BuilderRecoveryDrillTest {
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

    private JSONObject backup(JSONArray events, String status) throws Exception {
        return BuilderRecoveryExport.compose(conversation(), events, status,
                "unavailable".equals(status) ? "SQLiteException" : null);
    }

    @Test public void reportsReadyOnlyAfterIntegrityAndIndependentSourceMatch() throws Exception {
        JSONArray source = new JSONArray().put(event("event-1"));
        BuilderRecoveryDrill.Report report = BuilderRecoveryDrill.assess(
                backup(source, "available"), source);
        assertEquals("ready", report.readiness);
        assertEquals("match", report.sourceComparison);
        assertTrue(report.integrityValid);
        assertFalse(report.toJson().getBoolean("restoration_performed"));
    }

    @Test public void sourceMismatchPreventsReadyStatus() throws Exception {
        JSONArray exported = new JSONArray().put(event("event-1"));
        JSONArray other = new JSONArray().put(event("event-2"));
        BuilderRecoveryDrill.Report report = BuilderRecoveryDrill.assess(
                backup(exported, "available"), other);
        assertEquals("not_ready", report.readiness);
        assertEquals("mismatch", report.sourceComparison);
        assertTrue(report.findings.contains("source_provenance_mismatch"));
    }

    @Test public void missingIndependentCaptureProducesLimitedReadiness() throws Exception {
        JSONArray exported = new JSONArray().put(event("event-1"));
        BuilderRecoveryDrill.Report report = BuilderRecoveryDrill.assess(
                backup(exported, "available"), null);
        assertEquals("ready_with_limitations", report.readiness);
        assertEquals("not_run", report.sourceComparison);
    }

    @Test public void unavailableSourceJournalIsNotMisreportedAsEmpty() throws Exception {
        BuilderRecoveryDrill.Report report = BuilderRecoveryDrill.assess(
                backup(null, "unavailable"), null);
        assertEquals("unavailable", report.provenanceState);
        assertEquals("ready_with_limitations", report.readiness);
        assertEquals("not_applicable_unavailable", report.sourceComparison);
    }

    @Test public void checksumTamperingPreventsReadyStatus() throws Exception {
        JSONObject backup = backup(new JSONArray(), "empty");
        backup.put("turn_count", 42);
        BuilderRecoveryDrill.Report report = BuilderRecoveryDrill.assess(backup, new JSONArray());
        assertEquals("not_ready", report.readiness);
        assertFalse(report.integrityValid);
        assertFalse(report.checksumValid);
    }

    @Test public void nullBackupIsNotReadyAndHasNoSideEffects() throws Exception {
        BuilderRecoveryDrill.Report report = BuilderRecoveryDrill.assess(null, null);
        JSONObject json = report.toJson();
        assertEquals("not_ready", report.readiness);
        assertFalse(json.getBoolean("restoration_performed"));
        assertFalse(json.getBoolean("overwrite_performed"));
        assertFalse(json.getBoolean("deletion_performed"));
    }
}
