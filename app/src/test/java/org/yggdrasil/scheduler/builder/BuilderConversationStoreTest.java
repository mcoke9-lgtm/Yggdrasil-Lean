package org.yggdrasil.scheduler.builder;

import android.content.Context;

import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

import java.util.List;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
public class BuilderConversationStoreTest {
    private static final String DATABASE = "builder-conversation-store-test.db";
    private BuilderConversationStore store;

    private BuilderConversationStore openStore() {
        store = new BuilderConversationStore(RuntimeEnvironment.getApplication(), DATABASE);
        return store;
    }

    private BuilderConversationProtocol.Turn turn(
            String id, BuilderRole speaker, BuilderRole recipient, String replyTo,
            BuilderConversationProtocol.TurnType type, long timestamp, String body) {
        return new BuilderConversationProtocol.Turn(
                BuilderConversationProtocol.CURRENT_VERSION, "thread-recovery-test", id,
                speaker, recipient, replyTo, type,
                BuilderConversationProtocol.EvidenceStatus.PROPOSED, timestamp, body);
    }

    @After public void cleanUp() {
        if (store != null) store.close();
        Context context = RuntimeEnvironment.getApplication();
        context.deleteDatabase(DATABASE);
    }

    @Test public void appendsAndReadsTurnsInOriginalOrder() {
        BuilderConversationStore journal = openStore();
        journal.append(turn("t1", BuilderRole.HUGINN, null, null,
                BuilderConversationProtocol.TurnType.CONTRIBUTION, 100, "Set the objective."));
        journal.append(turn("t2", BuilderRole.KVASIR, BuilderRole.HUGINN, "t1",
                BuilderConversationProtocol.TurnType.QUESTION, 200, "Which evidence supports it?"));

        List<BuilderConversationProtocol.Turn> recovered =
                journal.readThread("thread-recovery-test");
        assertEquals(2, recovered.size());
        assertEquals("t1", recovered.get(0).turnId);
        assertEquals("t2", recovered.get(1).turnId);
        assertEquals(BuilderRole.KVASIR, recovered.get(1).speaker);
        assertEquals("t1", recovered.get(1).replyToTurnId);
    }

    @Test public void threadSurvivesClosingAndReopeningTheStore() {
        BuilderConversationStore first = openStore();
        first.append(turn("t1", BuilderRole.MARI, null, null,
                BuilderConversationProtocol.TurnType.CONTRIBUTION, 100, "A shared thread begins."));
        first.append(turn("t2", BuilderRole.MUNINN, BuilderRole.MARI, "t1",
                BuilderConversationProtocol.TurnType.HANDOFF, 200, "Preserve this context."));
        first.close();
        store = null;

        BuilderConversationStore reopened = openStore();
        List<BuilderConversationProtocol.Turn> recovered =
                reopened.readThread("thread-recovery-test");
        assertEquals(2, recovered.size());
        assertEquals(BuilderConversationProtocol.TurnType.HANDOFF, recovered.get(1).type);
        assertEquals("Preserve this context.", recovered.get(1).body);
    }

    @Test public void invalidReplyDoesNotPartiallyAppend() {
        BuilderConversationStore journal = openStore();
        journal.append(turn("t1", BuilderRole.HUGINN, null, null,
                BuilderConversationProtocol.TurnType.CONTRIBUTION, 100, "Original turn."));

        BuilderConversationProtocol.Turn invalid = turn("t2", BuilderRole.KVASIR,
                BuilderRole.HUGINN, "missing-turn",
                BuilderConversationProtocol.TurnType.RESPONSE, 200, "This must not persist.");
        assertThrows(IllegalArgumentException.class, () -> journal.append(invalid));

        List<BuilderConversationProtocol.Turn> recovered =
                journal.readThread("thread-recovery-test");
        assertEquals(1, recovered.size());
        assertEquals("t1", recovered.get(0).turnId);
    }

    @Test public void duplicateTurnIdIsRejectedWithoutChangingHistory() {
        BuilderConversationStore journal = openStore();
        journal.append(turn("t1", BuilderRole.HUGINN, null, null,
                BuilderConversationProtocol.TurnType.CONTRIBUTION, 100, "Original."));
        assertThrows(IllegalArgumentException.class, () -> journal.append(
                turn("t1", BuilderRole.MUNINN, null, null,
                        BuilderConversationProtocol.TurnType.CONTRIBUTION, 200, "Duplicate.")));
        assertEquals(1, journal.readThread("thread-recovery-test").size());
    }

    @Test public void returnedThreadSnapshotCannotBeModified() {
        BuilderConversationStore journal = openStore();
        journal.append(turn("t1", BuilderRole.BALDR, null, null,
                BuilderConversationProtocol.TurnType.CONTRIBUTION, 100, "Verified structure."));
        List<BuilderConversationProtocol.Turn> snapshot =
                journal.readThread("thread-recovery-test");
        assertThrows(UnsupportedOperationException.class, snapshot::clear);
    }

    @Test public void listsRecoverableThreadsByMostRecentActivity() {
        BuilderConversationStore journal = openStore();
        journal.append(turn("a1", BuilderRole.HUGINN, null, null,
                BuilderConversationProtocol.TurnType.CONTRIBUTION, 100, "Thread A starts."));
        journal.append(new BuilderConversationProtocol.Turn(
                BuilderConversationProtocol.CURRENT_VERSION, "thread-b", "b1",
                BuilderRole.MARI, null, null,
                BuilderConversationProtocol.TurnType.CONTRIBUTION,
                BuilderConversationProtocol.EvidenceStatus.PROPOSED, 100, "Thread B starts."));
        journal.append(turn("a2", BuilderRole.MUNINN, null, null,
                BuilderConversationProtocol.TurnType.CONTRIBUTION, 200, "Thread A resumes."));

        List<String> threadIds = journal.listThreadIds();
        assertEquals(2, threadIds.size());
        assertEquals("thread-recovery-test", threadIds.get(0));
        assertEquals("thread-b", threadIds.get(1));
        assertThrows(UnsupportedOperationException.class, threadIds::clear);
    }

    @Test public void rejectsBlankThreadLookup() {
        BuilderConversationStore journal = openStore();
        assertThrows(IllegalArgumentException.class, () -> journal.readThread("  "));
    }
}
