package org.yggdrasil.scheduler.builder;

import android.content.Context;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
public class BuilderSubjectThreadRecorderTest {
    private Context context;

    @Before public void setUp() {
        context = RuntimeEnvironment.getApplication();
        context.deleteDatabase("builder-subject-thread-index.db");
    }

    @After public void tearDown() {
        context.deleteDatabase("builder-subject-thread-index.db");
    }

    @Test public void recordsSubjectSubheadingsAndAllSelectedParticipants() {
        BuilderSubjectThreadRecorder.record(context, "thread-1", "subject.cryptography",
                Arrays.asList("CRYPTO-08", "CRYPTO-05"),
                Arrays.asList(BuilderRole.HODR, BuilderRole.MUNINN, BuilderRole.TYR, BuilderRole.BALDR));
        BuilderSubjectThreadIndex index = new BuilderSubjectThreadIndex(context);
        try {
            List<BuilderSubjectThreadIndex.Event> events = index.readThread("thread-1");
            assertEquals(7, events.size());
            assertEquals(BuilderSubjectThreadIndex.EventType.SUBJECT_ATTACHED, events.get(0).type);
            assertEquals("CRYPTO-08", events.get(1).subheadingId);
            assertEquals("CRYPTO-05", events.get(2).subheadingId);
            assertEquals("HODR", events.get(3).participant);
            assertEquals("BALDR", events.get(6).participant);
        } finally {
            index.close();
        }
    }

    @Test public void broadSubjectRecordsSubjectAndNineStewards() {
        BuilderSubjectThreadRecorder.record(context, "thread-2", "subject.cryptography",
                Collections.emptyList(), Arrays.asList(BuilderRole.values()));
        BuilderSubjectThreadIndex index = new BuilderSubjectThreadIndex(context);
        try {
            List<BuilderSubjectThreadIndex.Event> events = index.readThread("thread-2");
            assertEquals(10, events.size());
            assertEquals(BuilderSubjectThreadIndex.EventType.SUBJECT_ATTACHED, events.get(0).type);
            int participantCount = 0;
            for (BuilderSubjectThreadIndex.Event event : events) {
                if (event.type == BuilderSubjectThreadIndex.EventType.PARTICIPANT_SELECTED)
                    participantCount++;
            }
            assertEquals(9, participantCount);
        } finally {
            index.close();
        }
    }

    @Test public void invalidBatchDoesNotPersistPartialEvents() {
        try {
            BuilderSubjectThreadRecorder.record(context, "thread-3", "subject.cryptography",
                    Arrays.asList("CRYPTO-08", " "),
                    Collections.singletonList(BuilderRole.HODR));
            fail("blank subheading should be rejected");
        } catch (IllegalArgumentException expected) {
            // expected
        }
        BuilderSubjectThreadIndex index = new BuilderSubjectThreadIndex(context);
        try {
            assertTrue(index.readThread("thread-3").isEmpty());
        } finally {
            index.close();
        }
    }
}
