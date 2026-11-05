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
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
public class BuilderRecoveryOverlayAccessTest {
    private static final String GENERATION = "access-generation";
    private Context context;
    private BuilderConversationStore conversations;
    private BuilderSubjectThreadIndex subjects;
    private BuilderRecoveryStager.StageResult staged;

    @Before public void setUp() {
        context = RuntimeEnvironment.getApplication();
        BuilderRecoveryActivation.setDirectorySyncForTests(directory -> { });
        BuilderRecoveryActivation.setAtomicRenameForTests((source, target) -> {
            if (!source.renameTo(target))
                throw new java.io.IOException("Host test rename failed");
        });
        clearFiles();
        conversations = new BuilderConversationStore(context, "access-source-conversations.db");
        subjects = new BuilderSubjectThreadIndex(context, "access-source-subjects.db");
        conversations.append(turn("baseline-turn", 1000L));
        subjects.append(event("baseline-event", 1000L));
        JSONObject pack = BuilderRecoveryPackage.create(conversations, subjects);
        staged = BuilderRecoveryStager.stage(context, pack, GENERATION);
        BuilderRecoveryActivation.activate(context, staged);
        BuilderRecoveryOverlayStore overlay = BuilderRecoveryOverlayStore.create(context,
                staged.conversationDatabaseName, staged.subjectDatabaseName, GENERATION,
                staged.conversationDataSha256, staged.subjectDataSha256);
        overlay.close();
    }

    @After public void tearDown() {
        if (conversations != null) conversations.close();
        if (subjects != null) subjects.close();
        clearFiles();
        BuilderRecoveryActivation.setDirectorySyncForTests(null);
        BuilderRecoveryActivation.setAtomicRenameForTests(null);
    }

    private void clearFiles() {
        context.deleteDatabase("access-source-conversations.db");
        context.deleteDatabase("access-source-subjects.db");
        context.deleteDatabase("builder-recovery-" + GENERATION + "-conversations.db");
        context.deleteDatabase("builder-recovery-" + GENERATION + "-subjects.db");
        context.deleteDatabase(BuilderRecoveryOverlayStore.databaseNameForGeneration(GENERATION));
        new File(context.getFilesDir(), "builder-recovery-generation-" + GENERATION + ".json").delete();
        new File(context.getFilesDir(), "builder-recovery-generation-" + GENERATION + ".json.tmp").delete();
        new File(context.getFilesDir(), "builder-recovery-active.json").delete();
        new File(context.getFilesDir(), "builder-recovery-active.json.tmp").delete();
    }

    private static BuilderConversationProtocol.Turn turn(String id, long timestamp) {
        return new BuilderConversationProtocol.Turn(BuilderConversationProtocol.CURRENT_VERSION,
                "access-thread", id, BuilderConversationProtocol.Participant.USER, null, null,
                BuilderConversationProtocol.TurnType.CONTRIBUTION,
                BuilderConversationProtocol.EvidenceStatus.UNKNOWN, timestamp, id);
    }

    private static BuilderSubjectThreadIndex.Event event(String id, long timestamp) {
        return new BuilderSubjectThreadIndex.Event(id, "access-thread", "subject.recovery",
                BuilderSubjectThreadIndex.EventType.SUBJECT_ATTACHED, null, null, timestamp);
    }

    @Test public void activeSessionAppendsReadsAndExportsInsideOneScopedAccess() throws Exception {
        JSONObject result = BuilderRecoveryOverlayAccess.withActive(context, session -> {
            assertEquals(GENERATION, session.identity().generationId);
            session.append(turn("overlay-turn", 2000L));
            session.appendAll(Collections.singletonList(event("overlay-event", 2000L)));
            assertEquals(2, session.readThread("access-thread").size());
            assertEquals(2, session.readSubjectThread("access-thread").size());
            return session.exportMergedSnapshots();
        });
        assertEquals(GENERATION, result.getString("generation_id"));
        assertEquals(2, result.getJSONObject("conversation_snapshot").getInt("turn_count"));
        assertEquals(2, result.getJSONObject("subject_thread_snapshot").getInt("event_count"));
    }

    @Test public void retainedSessionCannotBeUsedAfterCallbackReturns() {
        AtomicReference<BuilderRecoveryOverlayAccess.Session> retained = new AtomicReference<>();
        BuilderRecoveryOverlayAccess.withActive(context, session -> {
            retained.set(session);
            return null;
        });
        try {
            retained.get().listThreadIds();
            fail("session must expire when the callback returns");
        } catch (IllegalStateException expected) {
            assertTrue(expected.getMessage().contains("no longer active"));
        }
    }

    @Test public void retainedSessionCannotBeUsedFromAnotherThread() throws Exception {
        AtomicReference<BuilderRecoveryOverlayAccess.Session> retained = new AtomicReference<>();
        BuilderRecoveryOverlayAccess.withActive(context, session -> {
            retained.set(session);
            return null;
        });
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newSingleThreadExecutor();
        try {
            java.util.concurrent.Future<?> future = pool.submit(() -> retained.get().listThreadIds());
            try {
                future.get();
                fail("session cannot cross thread boundary");
            } catch (java.util.concurrent.ExecutionException expected) {
                assertTrue(expected.getCause() instanceof IllegalStateException);
            }
        } finally {
            pool.shutdownNow();
        }
    }

    @Test public void missingOverlayFailsClosedWithoutFallback() {
        context.deleteDatabase(BuilderRecoveryOverlayStore.databaseNameForGeneration(GENERATION));
        try {
            BuilderRecoveryOverlayAccess.withActive(context, session -> null);
            fail("missing active overlay must fail closed");
        } catch (IllegalStateException expected) {
            assertTrue(expected.getMessage().contains("missing"));
        }
    }
}
