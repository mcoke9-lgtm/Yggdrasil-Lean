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
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
public class BuilderRecoveryActivationTest {
    private Context context;
    private BuilderConversationStore sourceConversations;
    private BuilderSubjectThreadIndex sourceSubjects;
    private static final String ONE = "activation-one";
    private static final String TWO = "activation-two";

    @Before public void setUp() {
        context = RuntimeEnvironment.getApplication();
        clearAll();
        sourceConversations = new BuilderConversationStore(context, "activation-source.db");
        sourceSubjects = new BuilderSubjectThreadIndex(context, "activation-source-subjects.db");
        sourceConversations.append(new BuilderConversationProtocol.Turn(
                BuilderConversationProtocol.CURRENT_VERSION, "activation-thread", "activation-turn",
                BuilderConversationProtocol.Participant.USER, null, null,
                BuilderConversationProtocol.TurnType.CONTRIBUTION,
                BuilderConversationProtocol.EvidenceStatus.UNKNOWN,
                System.currentTimeMillis(), "non-sensitive activation fixture"));
        sourceSubjects.append(new BuilderSubjectThreadIndex.Event("activation-event",
                "activation-thread", "subject.cryptography",
                BuilderSubjectThreadIndex.EventType.SUBJECT_ATTACHED, null, null,
                System.currentTimeMillis()));
    }

    @After public void tearDown() {
        sourceConversations.close();
        sourceSubjects.close();
        clearAll();
    }

    private void clearAll() {
        for (String id : new String[]{ONE,TWO}) {
            context.deleteDatabase("builder-recovery-" + id + "-conversations.db");
            context.deleteDatabase("builder-recovery-" + id + "-subjects.db");
            new File(context.getFilesDir(), "builder-recovery-generation-" + id + ".json").delete();
            new File(context.getFilesDir(), "builder-recovery-generation-" + id + ".json.tmp").delete();
        }
        context.deleteDatabase("activation-source.db");
        context.deleteDatabase("activation-source-subjects.db");
        new File(context.getFilesDir(), "builder-recovery-active.json").delete();
        new File(context.getFilesDir(), "builder-recovery-active.json.tmp").delete();
    }

    private BuilderRecoveryStager.StageResult stage(String id) {
        JSONObject recovery = BuilderRecoveryPackage.create(sourceConversations, sourceSubjects);
        return BuilderRecoveryStager.stage(context, recovery, id);
    }

    @Test public void switchesBothDatabasesThroughOnePointerAndRollsBackAsPair() {
        BuilderRecoveryStager.StageResult first = stage(ONE);
        BuilderRecoveryActivation.ActivePair activeOne =
                BuilderRecoveryActivation.activate(context, first);
        assertEquals(ONE, activeOne.generationId);
        assertNull(activeOne.previousGenerationId);

        BuilderRecoveryStager.StageResult second = stage(TWO);
        BuilderRecoveryActivation.ActivePair activeTwo =
                BuilderRecoveryActivation.activate(context, second);
        assertEquals(TWO, activeTwo.generationId);
        assertEquals(ONE, activeTwo.previousGenerationId);

        BuilderRecoveryActivation.ActivePair current = BuilderRecoveryActivation.getActive(context);
        assertEquals(TWO, current.generationId);
        assertEquals(second.conversationDatabaseName, current.conversationDatabaseName);
        assertEquals(second.subjectDatabaseName, current.subjectDatabaseName);

        BuilderRecoveryActivation.ActivePair rolledBack = BuilderRecoveryActivation.rollback(context);
        assertEquals(ONE, rolledBack.generationId);
        assertEquals(TWO, rolledBack.previousGenerationId);
        assertEquals(first.conversationDatabaseName,
                BuilderRecoveryActivation.getActive(context).conversationDatabaseName);

        assertTrue(context.getDatabasePath(first.conversationDatabaseName).isFile());
        assertTrue(context.getDatabasePath(first.subjectDatabaseName).isFile());
        assertTrue(context.getDatabasePath(second.conversationDatabaseName).isFile());
        assertTrue(context.getDatabasePath(second.subjectDatabaseName).isFile());
    }

    @Test public void refusesRollbackWhenNoPreviousGenerationExists() {
        BuilderRecoveryActivation.activate(context, stage(ONE));
        try {
            BuilderRecoveryActivation.rollback(context);
            fail("first activation has no previous generation");
        } catch (IllegalStateException expected) {
            assertTrue(expected.getMessage().contains("No previous"));
        }
        assertEquals(ONE, BuilderRecoveryActivation.getActive(context).generationId);
    }

    @Test public void refusesToGuessWhenActiveManifestChecksumIsDamaged() throws Exception {
        BuilderRecoveryActivation.activate(context, stage(ONE));
        File manifest = new File(context.getFilesDir(), "builder-recovery-active.json");
        String raw;
        try (java.io.FileInputStream in = new java.io.FileInputStream(manifest)) {
            byte[] bytes = new byte[(int) manifest.length()];
            int count = in.read(bytes);
            raw = new String(bytes, 0, count, java.nio.charset.StandardCharsets.UTF_8);
        }
        JSONObject damaged = new JSONObject(raw);
        damaged.put("active_generation_id", TWO);
        try (java.io.FileOutputStream out = new java.io.FileOutputStream(manifest, false)) {
            out.write(damaged.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
            out.getFD().sync();
        }
        try {
            BuilderRecoveryActivation.getActive(context);
            fail("damaged pointer must not be guessed");
        } catch (IllegalStateException expected) {
            assertTrue(expected.getMessage().contains("manifest is invalid"));
        }
    }

    @Test public void refusesActivationIfStagedContentChangesAfterReadBack() {
        BuilderRecoveryStager.StageResult staged = stage(ONE);
        android.database.sqlite.SQLiteDatabase database =
                android.database.sqlite.SQLiteDatabase.openDatabase(
                        context.getDatabasePath(staged.conversationDatabaseName).getAbsolutePath(),
                        null, android.database.sqlite.SQLiteDatabase.OPEN_READWRITE);
        try {
            database.execSQL("UPDATE conversation_turns SET body = ?", new Object[]{"altered after staging"});
        } finally {
            database.close();
        }
        try {
            BuilderRecoveryActivation.activate(context, staged);
            fail("activation must verify exact staged records, not counts alone");
        } catch (IllegalStateException expected) {
            assertTrue(expected.getMessage().contains("contents no longer match"));
        }
        assertNull(BuilderRecoveryActivation.getActive(context));
    }
}
