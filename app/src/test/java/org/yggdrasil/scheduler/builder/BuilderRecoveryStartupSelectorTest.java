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
public class BuilderRecoveryStartupSelectorTest {
    private Context context;
    private BuilderConversationStore sourceConversations;
    private BuilderSubjectThreadIndex sourceSubjects;
    private static final String GENERATION = "selector-generation";

    @Before public void setUp() {
        context = RuntimeEnvironment.getApplication();
        BuilderRecoveryActivation.setDirectorySyncForTests(directory -> { });
        BuilderRecoveryActivation.setAtomicRenameForTests((source, target) -> {
            if (!source.renameTo(target))
                throw new java.io.IOException("Host test rename failed: " + source.getName());
        });
        clearRecoveryFiles();
        sourceConversations = new BuilderConversationStore(context, "selector-source.db");
        sourceSubjects = new BuilderSubjectThreadIndex(context, "selector-source-subjects.db");
        sourceConversations.append(new BuilderConversationProtocol.Turn(
                BuilderConversationProtocol.CURRENT_VERSION, "selector-thread", "selector-turn",
                BuilderConversationProtocol.Participant.USER, null, null,
                BuilderConversationProtocol.TurnType.CONTRIBUTION,
                BuilderConversationProtocol.EvidenceStatus.UNKNOWN,
                System.currentTimeMillis(), "selector test fixture"));
        sourceSubjects.append(new BuilderSubjectThreadIndex.Event("selector-event",
                "selector-thread", "subject.continuity",
                BuilderSubjectThreadIndex.EventType.SUBJECT_ATTACHED, null, null,
                System.currentTimeMillis()));
    }

    @After public void tearDown() {
        sourceConversations.close();
        sourceSubjects.close();
        clearRecoveryFiles();
        context.deleteDatabase("selector-source.db");
        context.deleteDatabase("selector-source-subjects.db");
        BuilderRecoveryActivation.setDirectorySyncForTests(null);
        BuilderRecoveryActivation.setAtomicRenameForTests(null);
    }

    private void clearRecoveryFiles() {
        context.deleteDatabase("builder-recovery-" + GENERATION + "-conversations.db");
        context.deleteDatabase("builder-recovery-" + GENERATION + "-subjects.db");
        File files = context.getFilesDir();
        new File(files, "builder-recovery-active.json").delete();
        new File(files, "builder-recovery-active.json.tmp").delete();
        new File(files, "builder-recovery-generation-" + GENERATION + ".json").delete();
        new File(files, "builder-recovery-generation-" + GENERATION + ".json.tmp").delete();
    }

    private BuilderRecoveryStager.StageResult stageAndActivate() {
        JSONObject envelope = BuilderRecoveryPackage.create(sourceConversations, sourceSubjects);
        BuilderRecoveryStager.StageResult staged =
                BuilderRecoveryStager.stage(context, envelope, GENERATION);
        BuilderRecoveryActivation.activate(context, staged);
        return staged;
    }

    @Test public void noManifestSelectsCanonicalStoresWithoutCreatingRecoveryFiles() {
        BuilderRecoveryStartupSelector.Selection selection =
                BuilderRecoveryStartupSelector.resolve(context);
        assertEquals(BuilderRecoveryStartupSelector.Source.CANONICAL, selection.source);
        assertEquals("builder-conversations.db", selection.conversationDatabaseName);
        assertEquals("builder-subject-thread-index.db", selection.subjectDatabaseName);
        assertNull(selection.generationId);
        assertFalse(selection.usesRecovery());
        assertFalse(new File(context.getFilesDir(), "builder-recovery-active.json").exists());
    }

    @Test public void validManifestSelectsBothVerifiedRecoveryDatabasesAsOnePair() {
        BuilderRecoveryStager.StageResult staged = stageAndActivate();
        BuilderRecoveryStartupSelector.Selection selection =
                BuilderRecoveryStartupSelector.resolve(context);
        assertEquals(BuilderRecoveryStartupSelector.Source.RECOVERY, selection.source);
        assertTrue(selection.usesRecovery());
        assertEquals(GENERATION, selection.generationId);
        assertEquals(staged.conversationDatabaseName, selection.conversationDatabaseName);
        assertEquals(staged.subjectDatabaseName, selection.subjectDatabaseName);
        assertNull(selection.previousGenerationId);
    }

    @Test public void damagedManifestFailsClosedInsteadOfFallingBackToCanonical() throws Exception {
        stageAndActivate();
        File manifest = new File(context.getFilesDir(), "builder-recovery-active.json");
        JSONObject pointer = readJson(manifest);
        pointer.put("active_generation_id", "other-generation");
        writeJson(manifest, pointer);
        try {
            BuilderRecoveryStartupSelector.resolve(context);
            fail("damaged manifest must not select canonical stores");
        } catch (IllegalStateException expected) {
            assertTrue(expected.getMessage().contains("manifest is invalid"));
        }
    }

    @Test public void missingActiveDatabaseFailsClosedInsteadOfSelectingCanonical() {
        BuilderRecoveryStager.StageResult staged = stageAndActivate();
        context.deleteDatabase(staged.subjectDatabaseName);
        try {
            BuilderRecoveryStartupSelector.resolve(context);
            fail("incomplete recovery pair must not be selected");
        } catch (IllegalStateException expected) {
            assertTrue(expected.getMessage().contains("A staged database file is missing"));
        }
    }

    @Test public void selectorDoesNotChangeExistingCanonicalDatabaseFiles() {
        BuilderConversationStore canonical = new BuilderConversationStore(context);
        canonical.append(new BuilderConversationProtocol.Turn(
                BuilderConversationProtocol.CURRENT_VERSION, "canonical-thread", "canonical-turn",
                BuilderConversationProtocol.Participant.USER, null, null,
                BuilderConversationProtocol.TurnType.CONTRIBUTION,
                BuilderConversationProtocol.EvidenceStatus.UNKNOWN,
                System.currentTimeMillis(), "must remain untouched"));
        canonical.close();
        long before = context.getDatabasePath("builder-conversations.db").length();

        BuilderRecoveryStartupSelector.resolve(context);

        assertEquals(before, context.getDatabasePath("builder-conversations.db").length());
        BuilderConversationStore check = new BuilderConversationStore(context);
        assertEquals(1, check.readThread("canonical-thread").size());
        check.close();
    }

    private static JSONObject readJson(File file) throws Exception {
        byte[] bytes = new byte[(int) file.length()];
        try (java.io.FileInputStream in = new java.io.FileInputStream(file)) {
            int offset = 0;
            while (offset < bytes.length) {
                int count = in.read(bytes, offset, bytes.length - offset);
                if (count < 0) throw new java.io.IOException("Unexpected end of file");
                offset += count;
            }
        }
        return new JSONObject(new String(bytes, java.nio.charset.StandardCharsets.UTF_8));
    }

    private static void writeJson(File file, JSONObject value) throws Exception {
        try (java.io.FileOutputStream out = new java.io.FileOutputStream(file, false)) {
            out.write(value.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
            out.getFD().sync();
        }
    }
}
