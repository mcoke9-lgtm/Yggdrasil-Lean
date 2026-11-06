package org.yggdrasil.scheduler.builder;

import android.content.Context;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
public class BuilderJournalRecoveryTest {
    private Context context;

    @Before public void setUp() {
        context = RuntimeEnvironment.getApplication();
        context.deleteDatabase("yggdrasil_builder_continuity.db");
    }

    @After public void tearDown() {
        context.deleteDatabase("yggdrasil_builder_continuity.db");
    }

    private BuilderMessage sampleMessage() {
        return new BuilderMessage(1, "message-restore-test", "MARI", "MUNINN",
                1793850000000L, "task-restore-test", "text/plain", "preserve this handoff");
    }

    @Test public void snapshotRoundTripRestoresEventsAndMessageReceipt() throws Exception {
        BuilderEventJournal original = new BuilderEventJournal(context);
        assertTrue(original.append("event-1", "TASK_CREATED", "MARI", 1793850000001L, "task"));
        assertTrue(original.recordMessageOnce(sampleMessage(), 1793850000002L));
        assertTrue(original.verifyChain());
        JSONObject snapshot = original.exportSnapshot();
        original.close();

        context.deleteDatabase("yggdrasil_builder_continuity.db");
        BuilderEventJournal restored = new BuilderEventJournal(context);
        restored.restoreSnapshot(snapshot);

        assertEquals(2, restored.eventCount());
        assertTrue(restored.verifyChain());
        assertFalse("restored receipt must prevent duplicate processing",
                restored.recordMessageOnce(sampleMessage(), 1793850000003L));
        assertEquals(2, restored.eventCount());
        restored.close();
    }

    @Test public void invalidSnapshotDoesNotReplaceExistingJournal() throws Exception {
        BuilderEventJournal journal = new BuilderEventJournal(context);
        assertTrue(journal.append("keep-me", "NOTE", "MUNINN", 1793850000001L, "existing data"));
        JSONObject invalid = journal.exportSnapshot();
        JSONArray events = invalid.getJSONArray("events");
        events.getJSONObject(0).put("payload", "tampered data");

        try {
            journal.restoreSnapshot(invalid);
            fail("Tampered snapshot must be rejected");
        } catch (IllegalArgumentException expected) {
            // Expected: validation occurs before the database transaction.
        }
        assertEquals(1, journal.eventCount());
        assertTrue(journal.verifyChain());
        assertEquals("keep-me", journal.recentEvents(1).get(0).eventId);
        journal.close();
    }

    @Test public void unsupportedSnapshotVersionIsRejectedWithoutMutation() throws Exception {
        BuilderEventJournal journal = new BuilderEventJournal(context);
        assertTrue(journal.append("safe-event", "NOTE", "MARI", 1793850000001L, "safe"));
        JSONObject invalid = new JSONObject();
        invalid.put("format", "yggdrasil-builder-continuity");
        invalid.put("format_version", 99);
        try {
            journal.restoreSnapshot(invalid);
            fail("Unsupported snapshot version must be rejected");
        } catch (IllegalArgumentException expected) {
            // Expected.
        }
        assertEquals(1, journal.eventCount());
        assertTrue(journal.verifyChain());
        journal.close();
    }
}
