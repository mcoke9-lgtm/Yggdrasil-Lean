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
public class BuilderRecoveryOverlayExportPackageTest {
    private static final String SOURCE_GENERATION = "overlay-export-source";
    private Context context;
    private BuilderConversationStore sourceConversations;
    private BuilderSubjectThreadIndex sourceSubjects;
    private BuilderRecoveryStager.StageResult baselineStage;
    private BuilderRecoveryOverlayStore overlay;

    @Before public void setUp() {
        context = RuntimeEnvironment.getApplication();
        clearFiles();
        sourceConversations = new BuilderConversationStore(context, "overlay-export-source-conversations.db");
        sourceSubjects = new BuilderSubjectThreadIndex(context, "overlay-export-source-subjects.db");
        sourceConversations.append(turn("baseline-turn", 1000L));
        sourceSubjects.append(event("baseline-event", 1000L));
        JSONObject baseline = BuilderRecoveryPackage.create(sourceConversations, sourceSubjects);
        baselineStage = BuilderRecoveryStager.stage(context, baseline, SOURCE_GENERATION);
    }

    @After public void tearDown() {
        if (overlay != null) overlay.close();
        if (sourceConversations != null) sourceConversations.close();
        if (sourceSubjects != null) sourceSubjects.close();
        clearFiles();
        context.deleteDatabase("overlay-export-source-conversations.db");
        context.deleteDatabase("overlay-export-source-subjects.db");
    }

    private void clearFiles() {
        context.deleteDatabase("overlay-export-source-conversations.db");
        context.deleteDatabase("overlay-export-source-subjects.db");
        context.deleteDatabase("builder-recovery-" + SOURCE_GENERATION + "-conversations.db");
        context.deleteDatabase("builder-recovery-" + SOURCE_GENERATION + "-subjects.db");
        context.deleteDatabase(BuilderRecoveryOverlayStore.databaseNameForGeneration(SOURCE_GENERATION));
        context.deleteDatabase("builder-recovery-overlay-roundtrip-conversations.db");
        context.deleteDatabase("builder-recovery-overlay-roundtrip-subjects.db");
    }

    private static BuilderConversationProtocol.Turn turn(String id, long timestamp) {
        return new BuilderConversationProtocol.Turn(
                BuilderConversationProtocol.CURRENT_VERSION, "overlay-thread", id,
                BuilderConversationProtocol.Participant.USER, null, null,
                BuilderConversationProtocol.TurnType.CONTRIBUTION,
                BuilderConversationProtocol.EvidenceStatus.UNKNOWN, timestamp, id);
    }

    private static BuilderSubjectThreadIndex.Event event(String id, long timestamp) {
        return new BuilderSubjectThreadIndex.Event(id, "overlay-thread", "subject.continuity",
                BuilderSubjectThreadIndex.EventType.SUBJECT_ATTACHED, null, null, timestamp);
    }

    @Test public void mergedOverlayExportValidatesStagesAndCanBeExportedAgain() throws Exception {
        overlay = BuilderRecoveryOverlayStore.create(context,
                baselineStage.conversationDatabaseName, baselineStage.subjectDatabaseName,
                SOURCE_GENERATION, baselineStage.conversationDataSha256,
                baselineStage.subjectDataSha256);
        overlay.append(turn("overlay-turn", 2000L));
        overlay.appendAll(java.util.Collections.singletonList(event("overlay-event", 2000L)));

        JSONObject exported = overlay.exportMergedSnapshots();
        BuilderRecoveryOverlayExportPackage.ValidationReport report =
                BuilderRecoveryOverlayExportPackage.validate(exported);
        assertEquals(SOURCE_GENERATION, report.generationId);
        assertEquals(baselineStage.conversationDataSha256, report.conversationBaselineSha256);
        assertEquals(baselineStage.subjectDataSha256, report.subjectBaselineSha256);
        assertEquals(2, report.recovery.turnCount);
        assertEquals(2, report.recovery.subjectEventCount);

        BuilderRecoveryStager.StageResult staged =
                BuilderRecoveryOverlayExportPackage.stage(context, exported, "overlay-roundtrip");
        assertTrue(staged.readBackVerified);
        assertEquals(2, staged.turnCount);
        assertEquals(2, staged.subjectEventCount);

        BuilderConversationStore restoredConversations = new BuilderConversationStore(
                context, staged.conversationDatabaseName);
        BuilderSubjectThreadIndex restoredSubjects = new BuilderSubjectThreadIndex(
                context, staged.subjectDatabaseName);
        try {
            JSONObject secondExport = BuilderRecoveryPackage.create(
                    restoredConversations, restoredSubjects);
            BuilderRecoveryPackage.ValidationReport secondReport =
                    BuilderRecoveryPackage.validate(secondExport);
            assertEquals(2, secondReport.turnCount);
            assertEquals(2, secondReport.subjectEventCount);
            assertEquals(2, secondExport.getJSONObject("conversation_snapshot")
                    .getInt("turn_count"));
            assertEquals(2, secondExport.getJSONObject("subject_thread_snapshot")
                    .getInt("event_count"));
        } finally {
            restoredConversations.close();
            restoredSubjects.close();
        }
    }

    @Test public void rejectsTamperedOuterEnvelopeWithoutCreatingStagingFiles() throws Exception {
        overlay = BuilderRecoveryOverlayStore.create(context,
                baselineStage.conversationDatabaseName, baselineStage.subjectDatabaseName,
                SOURCE_GENERATION, baselineStage.conversationDataSha256,
                baselineStage.subjectDataSha256);
        JSONObject exported = overlay.exportMergedSnapshots();
        exported.put("generation_id", "tampered-generation");
        try {
            BuilderRecoveryOverlayExportPackage.stage(context, exported, "overlay-roundtrip");
            fail("tampered provenance must be rejected");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("checksum"));
        }
        assertFalse(context.getDatabasePath(
                "builder-recovery-overlay-roundtrip-conversations.db").exists());
        assertFalse(context.getDatabasePath(
                "builder-recovery-overlay-roundtrip-subjects.db").exists());
    }
}