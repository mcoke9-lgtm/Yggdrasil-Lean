package org.yggdrasil.scheduler.builder;

import android.content.Context;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import java.io.File;
import java.util.Collections;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
public class BuilderRecoveryConversationJournalTest {
    private static final String GENERATION = "journal-routing-test";
    private Context context;
    private BuilderConversationStore sourceConversations;
    private BuilderSubjectThreadIndex sourceSubjects;

    @Before public void setUp() {
        context = RuntimeEnvironment.getApplication();
        BuilderRecoveryActivation.setDirectorySyncForTests(directory -> { });
        BuilderRecoveryActivation.setAtomicRenameForTests((source, target) -> {
            if (!source.renameTo(target)) throw new java.io.IOException("Host rename failed");
        });
        clearFiles();
        sourceConversations = new BuilderConversationStore(context, "routing-source-conversations.db");
        sourceSubjects = new BuilderSubjectThreadIndex(context, "routing-source-subjects.db");
        sourceConversations.append(turn("baseline-turn", 1000L));
        sourceSubjects.append(event("baseline-event", 1000L));
    }

    @After public void tearDown() {
        sourceConversations.close();
        sourceSubjects.close();
        clearFiles();
        BuilderRecoveryActivation.setDirectorySyncForTests(null);
        BuilderRecoveryActivation.setAtomicRenameForTests(null);
    }

    private void clearFiles() {
        for (String name : new String[]{"routing-source-conversations.db",
                "routing-source-subjects.db", "builder-conversations.db",
                "builder-subject-thread-index.db",
                "builder-recovery-" + GENERATION + "-conversations.db",
                "builder-recovery-" + GENERATION + "-subjects.db",
                BuilderRecoveryOverlayStore.databaseNameForGeneration(GENERATION)})
            context.deleteDatabase(name);
        for (String name : new String[]{"builder-recovery-generation-" + GENERATION + ".json",
                "builder-recovery-generation-" + GENERATION + ".json.tmp",
                "builder-recovery-active.json", "builder-recovery-active.json.tmp"})
            new File(context.getFilesDir(), name).delete();
    }

    private static BuilderConversationProtocol.Turn turn(String id, long timestamp) {
        return new BuilderConversationProtocol.Turn(BuilderConversationProtocol.CURRENT_VERSION,
                "routing-thread", id, BuilderConversationProtocol.Participant.USER, null, null,
                BuilderConversationProtocol.TurnType.CONTRIBUTION,
                BuilderConversationProtocol.EvidenceStatus.UNKNOWN, timestamp, id);
    }

    private static BuilderSubjectThreadIndex.Event event(String id, long timestamp) {
        return new BuilderSubjectThreadIndex.Event(id, "routing-thread", "subject.routing",
                BuilderSubjectThreadIndex.EventType.SUBJECT_ATTACHED, null, null, timestamp);
    }

    private void activateFixture() {
        JSONObject pack = BuilderRecoveryPackage.create(sourceConversations, sourceSubjects);
        BuilderRecoveryStager.StageResult staged =
                BuilderRecoveryStager.stage(context, pack, GENERATION);
        BuilderRecoveryActivation.activate(context, staged);
    }

    @Test public void usesLegacyCanonicalStoreBeforeAnyGenerationIsActive() {
        BuilderRecoveryConversationJournal journal = new BuilderRecoveryConversationJournal(context);
        try {
            journal.append(turn("legacy-turn", 2000L));
            assertEquals(1, journal.readThread("routing-thread").size());
            assertEquals("legacy-turn", journal.readThread("routing-thread").get(0).turnId);
        } finally {
            journal.close();
        }
    }

    @Test public void routesCanonicalConversationWritesAndReadsThroughActiveOverlay() {
        activateFixture();
        BuilderRecoveryConversationJournal journal = new BuilderRecoveryConversationJournal(context);
        try {
            journal.append(turn("overlay-turn", 2000L));
            assertEquals(2, journal.readThread("routing-thread").size());
            assertEquals(2, journal.exportSnapshot().getInt("turn_count"));
        } finally {
            journal.close();
        }
        assertTrue(context.getDatabasePath(
                BuilderRecoveryOverlayStore.databaseNameForGeneration(GENERATION)).isFile());
        BuilderConversationStore legacy = new BuilderConversationStore(context);
        try {
            assertTrue("active writes must not leak into the legacy database",
                    legacy.listThreadIds().isEmpty());
        } finally {
            legacy.close();
        }
    }

    @Test public void routesSubjectRecorderWritesThroughActiveOverlay() {
        activateFixture();
        BuilderSubjectThreadRecorder.record(context, "routing-thread", "subject.after-activation",
                Collections.singletonList("ROUTING-01"),
                Collections.singletonList(BuilderRole.BALDR));
        JSONObject merged = BuilderRecoveryOverlayAccess.withActive(context,
                BuilderRecoveryOverlayAccess.Session::exportMergedSnapshots);
        assertEquals(4, merged.getJSONObject("subject_thread_snapshot").getInt("event_count"));
    }
}
