package org.yggdrasil.scheduler.builder;

import android.content.Context;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
public class BuilderSubjectThreadIndexTest {
    private Context context;
    private BuilderSubjectThreadIndex index;

    @Before public void setUp() {
        context = RuntimeEnvironment.getApplication();
        context.deleteDatabase("builder-subject-thread-index.db");
        index = new BuilderSubjectThreadIndex(context);
    }

    @After public void tearDown() {
        if (index != null) index.close();
        context.deleteDatabase("builder-subject-thread-index.db");
    }

    @Test public void oneThreadCanAccumulateSubjectsAndSubheadings() {
        index.append(new BuilderSubjectThreadIndex.Event("e1", "thread-1", "cryptography",
                BuilderSubjectThreadIndex.EventType.SUBJECT_ATTACHED, null, null, 1000));
        index.append(new BuilderSubjectThreadIndex.Event("e2", "thread-1", "cryptography",
                BuilderSubjectThreadIndex.EventType.SUBHEADING_ATTACHED, "android-signing", null, 2000));
        index.append(new BuilderSubjectThreadIndex.Event("e3", "thread-1", "identity",
                BuilderSubjectThreadIndex.EventType.SUBJECT_ATTACHED, null, null, 3000));
        assertEquals(3, index.readThread("thread-1").size());
        assertEquals("identity", index.readThread("thread-1").get(2).subjectId);
    }

    @Test public void readOrderFollowsAppendSequenceNotClockOrdering() {
        index.append(new BuilderSubjectThreadIndex.Event("e1", "thread-1", "cryptography",
                BuilderSubjectThreadIndex.EventType.SUBJECT_ATTACHED, null, null, 3000));
        index.append(new BuilderSubjectThreadIndex.Event("e2", "thread-1", "identity",
                BuilderSubjectThreadIndex.EventType.SUBJECT_ATTACHED, null, null, 2000));
        assertEquals("e1", index.readThread("thread-1").get(0).eventId);
        assertEquals("e2", index.readThread("thread-1").get(1).eventId);
    }

    @Test public void participantSelectionIsRecordedWithoutOwningConversationTurns() {
        index.append(new BuilderSubjectThreadIndex.Event("e1", "thread-1", "cryptography",
                BuilderSubjectThreadIndex.EventType.PARTICIPANT_SELECTED, null, "BALDR", 1000));
        BuilderSubjectThreadIndex.Event event = index.readThread("thread-1").get(0);
        assertEquals("BALDR", event.participant);
        assertEquals("cryptography", event.subjectId);
    }

    @Test public void exportIsMetadataOnlyAndCarriesIntegrityChecksum() throws Exception {
        index.append(new BuilderSubjectThreadIndex.Event("e1", "thread-1", "cryptography",
                BuilderSubjectThreadIndex.EventType.SUBJECT_ATTACHED, null, null, 1000));
        JSONObject snapshot = index.exportSnapshot();
        assertEquals("yggdrasil-subject-thread-index", snapshot.getString("format"));
        assertEquals(1, snapshot.getInt("event_count"));
        assertTrue(snapshot.getString("content_sha256").matches("[0-9a-f]{64}"));
        assertFalse(snapshot.toString().contains("conversation body"));
    }

    @Test(expected = IllegalArgumentException.class)
    public void subheadingEventRequiresSubheadingIdentity() {
        new BuilderSubjectThreadIndex.Event("e1", "thread-1", "cryptography",
                BuilderSubjectThreadIndex.EventType.SUBHEADING_ATTACHED, null, null, 1000);
    }

    @Test(expected = android.database.sqlite.SQLiteConstraintException.class)
    public void duplicateEventIdentityIsRejected() {
        BuilderSubjectThreadIndex.Event event = new BuilderSubjectThreadIndex.Event("same", "thread-1",
                "cryptography", BuilderSubjectThreadIndex.EventType.SUBJECT_ATTACHED, null, null, 1000);
        index.append(event);
        index.append(event);
    }

    @Test public void associationsSurviveCloseAndReopen() {
        index.append(new BuilderSubjectThreadIndex.Event("e1", "thread-1", "cryptography",
                BuilderSubjectThreadIndex.EventType.SUBJECT_ATTACHED, null, null, 1000));
        index.close();
        index = new BuilderSubjectThreadIndex(context);
        assertEquals(1, index.readThread("thread-1").size());
    }
}
