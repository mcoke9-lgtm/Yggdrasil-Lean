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
public class BuilderRecoveryStagerTest {
    private Context context;
    private BuilderConversationStore liveConversations;
    private BuilderSubjectThreadIndex liveSubjects;
    private static final String STAGING_ID = "recovery-stage-test";

    @Before public void setUp() {
        context = RuntimeEnvironment.getApplication();
        context.deleteDatabase("recovery-stage-live-conversations.db");
        context.deleteDatabase("builder-subject-thread-index.db");
        deleteStageFiles();
        liveConversations = new BuilderConversationStore(context, "recovery-stage-live-conversations.db");
        liveSubjects = new BuilderSubjectThreadIndex(context);
    }

    @After public void tearDown() {
        liveConversations.close();
        liveSubjects.close();
        context.deleteDatabase("recovery-stage-live-conversations.db");
        context.deleteDatabase("builder-subject-thread-index.db");
        deleteStageFiles();
    }

    private void addLiveData() {
        liveConversations.append(new BuilderConversationProtocol.Turn(
                BuilderConversationProtocol.CURRENT_VERSION, "thread-live", "turn-live",
                BuilderConversationProtocol.Participant.USER, null, null,
                BuilderConversationProtocol.TurnType.CONTRIBUTION,
                BuilderConversationProtocol.EvidenceStatus.UNKNOWN,
                System.currentTimeMillis(), "non-sensitive staging fixture"));
        java.util.List<BuilderSubjectThreadIndex.Event> events = new java.util.ArrayList<>();
        long now = System.currentTimeMillis();
        events.add(new BuilderSubjectThreadIndex.Event("event-live-1", "thread-live",
                "subject.cryptography", BuilderSubjectThreadIndex.EventType.SUBJECT_ATTACHED,
                null, null, now));
        events.add(new BuilderSubjectThreadIndex.Event("event-live-2", "thread-live",
                "subject.cryptography", BuilderSubjectThreadIndex.EventType.PARTICIPANT_SELECTED,
                null, "HUGINN", now + 1));
        liveSubjects.appendAll(events);
    }

    private void deleteStageFiles() {
        context.deleteDatabase("builder-recovery-" + STAGING_ID + "-conversations.db");
        context.deleteDatabase("builder-recovery-" + STAGING_ID + "-subjects.db");
    }

    @Test public void stagesBothHistoriesSeparatelyAndLeavesLiveDatabasesUntouched() {
        addLiveData();
        JSONObject recovery = BuilderRecoveryPackage.create(liveConversations, liveSubjects);
        BuilderRecoveryStager.StageResult result =
                BuilderRecoveryStager.stage(context, recovery, STAGING_ID);

        assertTrue(result.readBackVerified);
        assertEquals(1, result.threadCount);
        assertEquals(1, result.turnCount);
        assertEquals(2, result.subjectEventCount);
        assertEquals(1, liveConversations.readThread("thread-live").size());
        assertEquals(2, liveSubjects.readThread("thread-live").size());

        BuilderConversationStore stagedConversations =
                new BuilderConversationStore(context, result.conversationDatabaseName);
        BuilderSubjectThreadIndex stagedSubjects =
                new BuilderSubjectThreadIndex(context, result.subjectDatabaseName);
        try {
            assertEquals("turn-live", stagedConversations.readThread("thread-live").get(0).turnId);
            assertEquals(2, stagedSubjects.readThread("thread-live").size());
            assertEquals("HUGINN", stagedSubjects.readThread("thread-live").get(1).participant);
        } finally {
            stagedConversations.close();
            stagedSubjects.close();
        }
    }

    @Test public void refusesToOverwriteAnExistingStagingPair() {
        addLiveData();
        JSONObject recovery = BuilderRecoveryPackage.create(liveConversations, liveSubjects);
        BuilderRecoveryStager.stage(context, recovery, STAGING_ID);
        try {
            BuilderRecoveryStager.stage(context, recovery, STAGING_ID);
            fail("existing staging pair must not be overwritten");
        } catch (IllegalStateException expected) {
            assertTrue(expected.getMessage().contains("already exists"));
        }
        assertEquals(1, liveConversations.readThread("thread-live").size());
    }

    @Test public void rejectsInvalidPackageBeforeCreatingStagingFiles() throws Exception {
        JSONObject recovery = BuilderRecoveryPackage.create(liveConversations, liveSubjects);
        recovery.put("format_version", 99);
        try {
            BuilderRecoveryStager.stage(context, recovery, STAGING_ID);
            fail("invalid recovery package must be rejected");
        } catch (IllegalArgumentException expected) {
            assertFalse(context.getDatabasePath("builder-recovery-" + STAGING_ID + "-conversations.db").exists());
            assertFalse(context.getDatabasePath("builder-recovery-" + STAGING_ID + "-subjects.db").exists());
        }
    }

    @Test public void rejectsUnsafeStagingIdentifiers() {
        JSONObject recovery = BuilderRecoveryPackage.create(liveConversations, liveSubjects);
        try {
            BuilderRecoveryStager.stage(context, recovery, "../live");
            fail("path-like staging identifiers must be rejected");
        } catch (IllegalArgumentException expected) {
            assertFalse(context.getDatabasePath("builder-recovery-" + STAGING_ID + "-conversations.db").exists());
        }
    }
}
