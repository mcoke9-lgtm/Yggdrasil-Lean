package org.yggdrasil.scheduler.builder;

import static org.junit.Assert.*;
import android.content.Context;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.RobolectricTestRunner;
import java.util.Arrays;

@RunWith(RobolectricTestRunner.class)
public class BuilderOperationalSnapshotJournalTest {
    @Test public void appendsDigestAndBoundedProvenanceWithoutCopyingWholeSnapshot() throws Exception {
        Context context = RuntimeEnvironment.getApplication();
        context.deleteDatabase("builder-operational-snapshots.db");
        String snapshot = "{\"schema\":\"yggdrasil.operational-capacity-register\","
                + "\"schema_version\":\"1.0.0\",\"register_status\":\"LOADED\","
                + "\"runtime_evaluation\":{\"evaluated_on\":\"2026-11-05\"},"
                + "\"constraints\":[{\"constraint_id\":\"tasks\","
                + "\"capability\":\"Create scheduled tasks\","
                + "\"observed_at\":\"2026-11-05\","
                + "\"evidence_source\":\"authoritative response\","
                + "\"gate_decision\":\"BLOCKED\"}]}";
        BuilderOperationalSnapshotJournal journal = new BuilderOperationalSnapshotJournal(context);
        String id = journal.append("thread-1", snapshot,
                Arrays.asList(BuilderRole.MUNINN, BuilderRole.BALDR), 123456L);
        assertNotNull(id);
        org.json.JSONArray events = journal.exportEvents();
        assertEquals(1, events.length());
        JSONObject event = events.getJSONObject(0);
        assertEquals("thread-1", event.getString("thread_id"));
        assertEquals("LOADED", event.getString("register_status"));
        assertEquals(64, event.getString("snapshot_sha256").length());
        assertEquals("2026-11-05", event.getString("evaluated_on"));
        assertEquals("MUNINN", event.getJSONArray("selected_builders").getString(0));
        assertEquals("authoritative response",
                event.getJSONArray("evidence_refs").getJSONObject(0).getString("evidence_source"));
        assertFalse(event.has("full_snapshot"));
        journal.close();
        context.deleteDatabase("builder-operational-snapshots.db");
    }

    @Test public void unavailableSnapshotIsStillRecordedAsUnavailable() throws Exception {
        Context context = RuntimeEnvironment.getApplication();
        context.deleteDatabase("builder-operational-snapshots.db");
        BuilderOperationalSnapshotJournal journal = new BuilderOperationalSnapshotJournal(context);
        journal.append("thread-2",
                "{\"register_status\":\"UNAVAILABLE\",\"constraints\":[]}",
                Arrays.asList(BuilderRole.HUGINN), 123457L);
        assertEquals("UNAVAILABLE",
                journal.exportEvents().getJSONObject(0).getString("register_status"));
        journal.close();
        context.deleteDatabase("builder-operational-snapshots.db");
    }
}
