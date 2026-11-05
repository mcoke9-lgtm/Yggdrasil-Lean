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
public class BuilderRecoveryPackageTest {
    private Context context;
    private BuilderConversationStore conversations;
    private BuilderSubjectThreadIndex subjects;

    @Before public void setUp() {
        context = RuntimeEnvironment.getApplication();
        context.deleteDatabase("recovery-test-conversations.db");
        context.deleteDatabase("builder-subject-thread-index.db");
        conversations = new BuilderConversationStore(context, "recovery-test-conversations.db");
        subjects = new BuilderSubjectThreadIndex(context);
    }

    @After public void tearDown() {
        conversations.close();
        subjects.close();
        context.deleteDatabase("recovery-test-conversations.db");
        context.deleteDatabase("builder-subject-thread-index.db");
    }

    private void addConversationTurn(String threadId, String turnId) {
        conversations.append(new BuilderConversationProtocol.Turn(
                BuilderConversationProtocol.CURRENT_VERSION, threadId, turnId,
                BuilderConversationProtocol.Participant.USER, null, null,
                BuilderConversationProtocol.TurnType.CONTRIBUTION,
                BuilderConversationProtocol.EvidenceStatus.UNKNOWN,
                System.currentTimeMillis(), "recovery test content"));
    }

    @Test public void packageKeepsBothHistoriesSeparateAndValidatesCounts() {
        addConversationTurn("thread-1", "turn-1");
        subjects.append(new BuilderSubjectThreadIndex.Event("event-1", "thread-1", "subject.cryptography",
                BuilderSubjectThreadIndex.EventType.SUBJECT_ATTACHED, null, null,
                System.currentTimeMillis()));
        JSONObject recovery = BuilderRecoveryPackage.create(conversations, subjects);
        BuilderRecoveryPackage.ValidationReport report = BuilderRecoveryPackage.validate(recovery);
        assertEquals(1, report.threadCount);
        assertEquals(1, report.turnCount);
        assertEquals(1, report.subjectEventCount);
        assertTrue(report.orphanSubjectThreadIds.isEmpty());
        assertTrue(recovery.has("conversation_snapshot"));
        assertTrue(recovery.has("subject_thread_snapshot"));
    }

    @Test public void reportsSubjectAssociationsWithoutCanonicalThreadAsOrphans() {
        addConversationTurn("thread-1", "turn-1");
        subjects.append(new BuilderSubjectThreadIndex.Event("event-orphan", "thread-not-created",
                "subject.cryptography", BuilderSubjectThreadIndex.EventType.SUBJECT_ATTACHED,
                null, null, System.currentTimeMillis()));
        BuilderRecoveryPackage.ValidationReport report =
                BuilderRecoveryPackage.validate(BuilderRecoveryPackage.create(conversations, subjects));
        assertEquals(1, report.orphanSubjectThreadIds.size());
        assertEquals("thread-not-created", report.orphanSubjectThreadIds.get(0));
    }

    @Test public void rejectsModifiedPackageBeforeAnyRestoreCanBegin() throws Exception {
        addConversationTurn("thread-1", "turn-1");
        JSONObject recovery = BuilderRecoveryPackage.create(conversations, subjects);
        recovery.put("format_version", 999);
        try {
            BuilderRecoveryPackage.validate(recovery);
            fail("modified package should be rejected");
        } catch (IllegalArgumentException expected) {
            // expected
        }
        assertEquals(1, conversations.readThread("thread-1").size());
    }

    @Test public void rejectsCorruptedNestedSnapshotEvenWhenOuterChecksumIsRecomputed() throws Exception {
        addConversationTurn("thread-1", "turn-1");
        JSONObject recovery = BuilderRecoveryPackage.create(conversations, subjects);
        JSONObject nested = recovery.getJSONObject("conversation_snapshot");
        nested.put("turn_count", 99);
        // Recreate outer checksum after deliberately corrupting the inner snapshot.
        // The nested checksum must still detect the change.
        recovery = new JSONObject(recovery.toString());
        recovery.remove("content_sha256");
        byte[] digest = java.security.MessageDigest.getInstance("SHA-256")
                .digest(recovery.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
        StringBuilder hex = new StringBuilder();
        for (byte b : digest) hex.append(String.format(java.util.Locale.ROOT, "%02x", b & 0xff));
        recovery.put("content_sha256", hex.toString());
        try {
            BuilderRecoveryPackage.validate(recovery);
            fail("corrupted nested snapshot should be rejected");
        } catch (IllegalArgumentException expected) {
            // expected
        }
    }
}
