package org.yggdrasil.scheduler.builder;

import static org.junit.Assert.*;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

public class BuilderRecoveryExportTest {
    private JSONObject conversation() throws Exception {
        JSONObject root = new JSONObject();
        root.put("format", "yggdrasil-nine-builder-conversations");
        root.put("format_version", 1);
        root.put("thread_count", 1);
        root.put("turn_count", 2);
        root.put("threads", new JSONArray().put(new JSONObject()
                .put("thread_id", "thread-a").put("turns", new JSONArray())));
        root.put("content_sha256", "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa");
        return root;
    }

    @Test public void completeExportIncludesEventsAndChecksumsCombinedContent() throws Exception {
        JSONArray events = new JSONArray().put(new JSONObject()
                .put("event_id", "event-1").put("snapshot_sha256", "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"));
        JSONObject result = BuilderRecoveryExport.compose(conversation(), events, "available", null);
        assertEquals(2, result.getInt("format_version"));
        assertEquals(1, result.getInt("thread_count"));
        assertEquals(2, result.getInt("turn_count"));
        JSONObject provenance = result.getJSONObject("operational_snapshot_provenance");
        assertEquals("available", provenance.getString("status"));
        assertEquals(1, provenance.getInt("event_count"));
        assertEquals("event-1", provenance.getJSONArray("events").getJSONObject(0).getString("event_id"));
        assertTrue(BuilderRecoveryExport.verifyChecksum(result));
    }

    @Test public void emptyProvenanceIsExplicitAndValid() throws Exception {
        JSONObject result = BuilderRecoveryExport.compose(conversation(), new JSONArray(), "empty", null);
        JSONObject provenance = result.getJSONObject("operational_snapshot_provenance");
        assertEquals("empty", provenance.getString("status"));
        assertEquals(0, provenance.getInt("event_count"));
        assertEquals(0, provenance.getJSONArray("events").length());
        assertTrue(BuilderRecoveryExport.verifyChecksum(result));
    }

    @Test public void unavailableProvenanceIsNotMisrepresentedAsEmpty() throws Exception {
        JSONObject result = BuilderRecoveryExport.compose(conversation(), null,
                "unavailable", "SQLiteException");
        JSONObject provenance = result.getJSONObject("operational_snapshot_provenance");
        assertEquals("unavailable", provenance.getString("status"));
        assertTrue(provenance.isNull("events"));
        assertTrue(provenance.isNull("event_count"));
        assertEquals("SQLiteException", provenance.getString("error_class"));
        assertTrue(BuilderRecoveryExport.verifyChecksum(result));
    }

    @Test public void checksumDetectsChangesToConversationOrProvenance() throws Exception {
        JSONObject result = BuilderRecoveryExport.compose(conversation(), new JSONArray(), "empty", null);
        assertTrue(BuilderRecoveryExport.verifyChecksum(result));
        result.getJSONObject("operational_snapshot_provenance").put("status", "unavailable");
        assertFalse(BuilderRecoveryExport.verifyChecksum(result));
    }

    @Test public void unsupportedFormatAndInvalidStatusAreRejected() throws Exception {
        JSONObject invalid = conversation();
        invalid.put("format", "other");
        try {
            BuilderRecoveryExport.compose(invalid, new JSONArray(), "empty", null);
            fail("Unsupported format must be rejected");
        } catch (IllegalArgumentException expected) { }
        try {
            BuilderRecoveryExport.compose(conversation(), new JSONArray(), "missing", null);
            fail("Invalid provenance status must be rejected");
        } catch (IllegalArgumentException expected) { }
    }
}
