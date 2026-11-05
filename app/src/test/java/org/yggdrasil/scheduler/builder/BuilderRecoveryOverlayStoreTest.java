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
public class BuilderRecoveryOverlayStoreTest {
    private static final String GENERATION = "overlay-generation";
    private Context context;
    private BuilderConversationStore sourceConversations;
    private BuilderSubjectThreadIndex sourceSubjects;
    private BuilderRecoveryStager.StageResult staged;
    private BuilderRecoveryOverlayStore overlay;

    @Before public void setUp() {
        context = RuntimeEnvironment.getApplication();
        clearFiles();
        sourceConversations = new BuilderConversationStore(context, "overlay-source-conversations.db");
        sourceSubjects = new BuilderSubjectThreadIndex(context, "overlay-source-subjects.db");
        sourceConversations.append(turn("base-turn", 1000L, null,
                BuilderConversationProtocol.TurnType.CONTRIBUTION));
        sourceSubjects.append(event("base-event", 1000L));
        JSONObject recovery = BuilderRecoveryPackage.create(sourceConversations, sourceSubjects);
        staged = BuilderRecoveryStager.stage(context, recovery, GENERATION);
    }

    @After public void tearDown() {
        if (overlay != null) overlay.close();
        if (sourceConversations != null) sourceConversations.close();
        if (sourceSubjects != null) sourceSubjects.close();
        clearFiles();
        context.deleteDatabase("overlay-source-conversations.db");
        context.deleteDatabase("overlay-source-subjects.db");
    }

    private void clearFiles() {
        context.deleteDatabase("overlay-source-conversations.db");
        context.deleteDatabase("overlay-source-subjects.db");
        context.deleteDatabase("builder-recovery-" + GENERATION + "-conversations.db");
        context.deleteDatabase("builder-recovery-" + GENERATION + "-subjects.db");
        context.deleteDatabase(BuilderRecoveryOverlayStore.databaseNameForGeneration(GENERATION));
    }

    private BuilderRecoveryOverlayStore openOverlay() {
        return new BuilderRecoveryOverlayStore(context,
                staged.conversationDatabaseName, staged.subjectDatabaseName, GENERATION,
                staged.conversationDataSha256, staged.subjectDataSha256);
    }

    private static BuilderConversationProtocol.Turn turn(String id, long timestamp,
            String replyTo, BuilderConversationProtocol.TurnType type) {
        return new BuilderConversationProtocol.Turn(
                BuilderConversationProtocol.CURRENT_VERSION, "overlay-thread", id,
                BuilderConversationProtocol.Participant.USER, null, replyTo, type,
                BuilderConversationProtocol.EvidenceStatus.UNKNOWN, timestamp, id);
    }

    private static BuilderSubjectThreadIndex.Event event(String id, long timestamp) {
        return new BuilderSubjectThreadIndex.Event(id, "overlay-thread", "subject.continuity",
                BuilderSubjectThreadIndex.EventType.SUBJECT_ATTACHED, null, null, timestamp);
    }

    @Test public void appendsRemainSeparateAndBaselineDigestStillVerifiesAfterReopen() {
        overlay = openOverlay();
        overlay.append(turn("overlay-turn", 2000L, null,
                BuilderConversationProtocol.TurnType.CONTRIBUTION));
        overlay.appendAll(java.util.Collections.singletonList(event("overlay-event", 2000L)));
        assertEquals(2, overlay.readThread("overlay-thread").size());
        assertEquals(2, overlay.readSubjectThread("overlay-thread").size());
        overlay.close();

        overlay = openOverlay();
        assertEquals(2, overlay.readThread("overlay-thread").size());
        assertEquals(2, overlay.readSubjectThread("overlay-thread").size());
        assertEquals(GENERATION, overlay.identity().generationId);
        assertEquals(staged.conversationDataSha256,
                overlay.identity().conversationBaselineSha256);
        assertEquals(staged.subjectDataSha256, overlay.identity().subjectBaselineSha256);
        // Opening the new helper rechecks both exact baseline record-array digests.
        assertEquals(2, overlay.exportConversationSnapshot().getInt("turn_count"));
        assertEquals(2, overlay.exportSubjectSnapshot().getInt("event_count"));
    }

    @Test public void duplicateConversationIdentityAcrossLayersIsRejected() {
        overlay = openOverlay();
        try {
            overlay.append(turn("base-turn", 2000L, null,
                    BuilderConversationProtocol.TurnType.CONTRIBUTION));
            fail("baseline turn identity must not be reused");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("Duplicate turn ID"));
        }
        assertEquals(1, overlay.readThread("overlay-thread").size());
    }

    @Test public void duplicateSubjectIdentityAcrossLayersIsRejected() {
        overlay = openOverlay();
        try {
            overlay.appendAll(java.util.Collections.singletonList(event("base-event", 2000L)));
            fail("baseline event identity must not be reused");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("Duplicate subject event ID"));
        }
        assertEquals(1, overlay.readSubjectThread("overlay-thread").size());
    }

    @Test public void invalidReplyToMissingTurnIsRejectedWithoutWriting() {
        overlay = openOverlay();
        BuilderConversationProtocol.Turn invalid = new BuilderConversationProtocol.Turn(
                BuilderConversationProtocol.CURRENT_VERSION, "overlay-thread", "bad-reply",
                BuilderConversationProtocol.Participant.HUGINN,
                BuilderConversationProtocol.Participant.USER, "missing-turn",
                BuilderConversationProtocol.TurnType.RESPONSE,
                BuilderConversationProtocol.EvidenceStatus.UNKNOWN, 2000L, "invalid");
        try {
            overlay.append(invalid);
            fail("reply to a missing turn must be rejected");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("earlier turn"));
        }
        assertEquals(1, overlay.readThread("overlay-thread").size());
    }

    @Test public void subjectBatchIsAllOrNothingWhenOneIdConflictsWithBaseline() {
        overlay = openOverlay();
        java.util.List<BuilderSubjectThreadIndex.Event> batch = java.util.Arrays.asList(
                event("new-event", 2000L), event("base-event", 3000L));
        try {
            overlay.appendAll(batch);
            fail("batch containing a baseline identity must fail");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("Duplicate subject event ID"));
        }
        assertEquals(1, overlay.readSubjectThread("overlay-thread").size());
    }

    @Test public void anotherHelperInstanceSeesTheSameGenerationBoundOverlay() {
        overlay = openOverlay();
        BuilderRecoveryOverlayStore second = openOverlay();
        try {
            overlay.appendAll(java.util.Collections.singletonList(event("from-first", 2000L)));
            assertEquals(2, second.readSubjectThread("overlay-thread").size());
            assertEquals(GENERATION, second.identity().generationId);
        } finally {
            second.close();
        }
    }

    @Test public void overlayCannotBeOpenedWithDifferentBaselineDigest() {
        overlay = openOverlay();
        overlay.close();
        overlay = new BuilderRecoveryOverlayStore(context,
                staged.conversationDatabaseName, staged.subjectDatabaseName, GENERATION,
                "0".repeat(64), staged.subjectDataSha256);
        try {
            overlay.getReadableDatabase();
            fail("overlay metadata must reject a different baseline digest");
        } catch (IllegalStateException expected) {
            assertTrue(expected.getMessage().contains("identity does not match"));
        }
    }

    @Test public void generationMustMatchTheBaselineDatabaseNames() {
        try {
            new BuilderRecoveryOverlayStore(context,
                    staged.conversationDatabaseName, staged.subjectDatabaseName, "other-generation",
                    staged.conversationDataSha256, staged.subjectDataSha256);
            fail("generation mismatch must fail before opening a new overlay");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("do not match generation"));
        }
    }
}
