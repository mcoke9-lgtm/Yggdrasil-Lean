package org.yggdrasil.scheduler.builder;

import android.content.Context;
import android.database.Cursor;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
public class BuilderEventJournalRecoverySequenceTest {
    private static final String DATABASE = "yggdrasil_builder_continuity.db";
    private Context context;
    private BuilderEventJournal journal;

    @Before public void setUp() {
        context = RuntimeEnvironment.getApplication();
        context.deleteDatabase(DATABASE);
        journal = new BuilderEventJournal(context);
    }

    @After public void tearDown() {
        if (journal != null) journal.close();
        context.deleteDatabase(DATABASE);
    }

    private static void append(BuilderEventJournal target, int n) {
        assertTrue(target.append("event-" + n, "TEST", "test", 1000L + n, "payload-" + n));
    }

    @Test public void restoreResetsAutoincrementHighWaterMarkAndNextSnapshotRemainsRestorable()
            throws Exception {
        append(journal, 1);
        append(journal, 2);
        JSONObject twoEventBaseline = journal.exportSnapshot();

        // Simulate a database that had accumulated more rows before restoring an older backup.
        for (int i = 3; i <= 40; i++) append(journal, i);
        journal.restoreSnapshot(twoEventBaseline);
        assertEquals(2L, journal.eventCount());

        append(journal, 41);
        assertEquals(3L, journal.eventCount());

        // This is the regression assertion: the new export must still have contiguous
        // sequence numbers and therefore be accepted by the same restore validator.
        JSONObject afterAppend = journal.exportSnapshot();
        try (Cursor c = journal.getReadableDatabase().rawQuery(
                "SELECT sequence FROM events ORDER BY sequence ASC", null)) {
            assertTrue(c.moveToFirst());
            assertEquals(1L, c.getLong(0));
            assertTrue(c.moveToNext());
            assertEquals(2L, c.getLong(0));
            assertTrue(c.moveToNext());
            assertEquals(3L, c.getLong(0));
            assertFalse(c.moveToNext());
        }
        journal.restoreSnapshot(afterAppend);
        assertEquals(3L, journal.eventCount());
        assertTrue(journal.verifyChain());
    }

    @Test public void restoringEmptySnapshotAllowsSequenceToRestartAtOne() {
        JSONObject empty = journal.exportSnapshot();
        for (int i = 1; i <= 10; i++) append(journal, i);
        journal.restoreSnapshot(empty);
        append(journal, 11);
        try (Cursor c = journal.getReadableDatabase().rawQuery(
                "SELECT sequence FROM events ORDER BY sequence ASC", null)) {
            assertTrue(c.moveToFirst());
            assertEquals(1L, c.getLong(0));
            assertFalse(c.moveToNext());
        }
    }
}