package org.yggdrasil.scheduler.builder;

import android.content.Context;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

import java.util.List;

import static org.junit.Assert.*;

/** Failure-injection tests for retrying a partially persisted assignment journal handoff. */
@RunWith(RobolectricTestRunner.class)
public class CharacterAssignmentJournalRecoveryTest {
    private static final String ASSIGNMENT_DB = CharacterAssignmentStore.DATABASE_NAME;
    private static final String JOURNAL_DB = "mdm-journal-recovery-test.db";
    private Context context;
    private CharacterAssignmentStore assignments;
    private BuilderConversationStore journal;

    @Before public void setUp() {
        context = RuntimeEnvironment.getApplication();
        context.deleteDatabase(ASSIGNMENT_DB);
        context.deleteDatabase(JOURNAL_DB);
        assignments = new CharacterAssignmentStore(context);
        journal = new BuilderConversationStore(context, JOURNAL_DB);
    }

    @After public void tearDown() {
        if (journal != null) journal.close();
        if (assignments != null) assignments.close();
        context.deleteDatabase(ASSIGNMENT_DB);
        context.deleteDatabase(JOURNAL_DB);
    }

    private CharacterAssignmentStore.Assignment createAccepted(String id) {
        assignments.create(id, "HUGINN", "ODIN", "Observe PR " + id,
                "Read-only", "{\"repository_write\":false}", "{\"max_checks\":1}", "CI result");
        assignments.transition(id, CharacterAssignmentStore.Status.AUTHORIZED, "Týr authorization");
        assignments.transition(id, CharacterAssignmentStore.Status.QUEUED, "Queued");
        assignments.transition(id, CharacterAssignmentStore.Status.ACCEPTED, "Claimed");
        return assignments.get(id);
    }

    private static final class FailOnceOnAcknowledgement implements BuilderConversationJournal {
        private final BuilderConversationJournal delegate;
        private boolean failed;

        FailOnceOnAcknowledgement(BuilderConversationJournal delegate) { this.delegate = delegate; }

        @Override public void append(BuilderConversationProtocol.Turn turn) {
            if (!failed && turn.type == BuilderConversationProtocol.TurnType.ACKNOWLEDGEMENT) {
                failed = true;
                throw new IllegalStateException("Injected interruption after request append");
            }
            delegate.append(turn);
        }
        @Override public List<BuilderConversationProtocol.Turn> readThread(String id) {
            return delegate.readThread(id);
        }
        @Override public List<String> listThreadIds() { return delegate.listThreadIds(); }
        @Override public org.json.JSONObject exportSnapshot() { return delegate.exportSnapshot(); }
        @Override public void close() { /* The test owns the delegate lifecycle. */ }
    }

    @Test public void retryCompletesPartialRequestWithoutDuplicatingIt() {
        CharacterAssignmentStore.Assignment assignment = createAccepted("recovery-001");
        FailOnceOnAcknowledgement interrupted = new FailOnceOnAcknowledgement(journal);

        try {
            CharacterAssignmentJournalBridge.appendAccepted(assignment, interrupted);
            fail("Expected injected interruption");
        } catch (IllegalStateException expected) {
            assertEquals("Injected interruption after request append", expected.getMessage());
        }

        String threadId = CharacterAssignmentJournalBridge.threadId("recovery-001");
        List<BuilderConversationProtocol.Turn> partial = journal.readThread(threadId);
        assertEquals(1, partial.size());
        assertEquals(BuilderConversationProtocol.TurnType.CONTRIBUTION, partial.get(0).type);

        // Simulate process restart: reopen the same durable journal and retry the bridge.
        journal.close();
        journal = new BuilderConversationStore(context, JOURNAL_DB);
        assignment = assignments.get("recovery-001");
        CharacterAssignmentJournalBridge.appendAccepted(assignment, journal);
        CharacterAssignmentJournalBridge.appendReturnReport(assignment,
                "{\"outcome\":\"BLOCKED\",\"execution_started\":false}", journal);
        CharacterAssignmentJournalBridge.appendAccepted(assignment, journal);
        CharacterAssignmentJournalBridge.appendReturnReport(assignment,
                "{\"outcome\":\"BLOCKED\",\"execution_started\":false}", journal);

        List<BuilderConversationProtocol.Turn> recovered = journal.readThread(threadId);
        assertEquals(3, recovered.size());
        assertEquals(1, count(recovered, BuilderConversationProtocol.TurnType.CONTRIBUTION));
        assertEquals(1, count(recovered, BuilderConversationProtocol.TurnType.ACKNOWLEDGEMENT));
        assertEquals(1, count(recovered, BuilderConversationProtocol.TurnType.RESPONSE));
        for (BuilderConversationProtocol.Turn turn : recovered) {
            assertEquals("recovery-001", turn.assignmentId);
        }
    }

    @Test public void twoAssignmentsPersistIntoDistinctThreads() {
        CharacterAssignmentStore.Assignment first = createAccepted("separate-001");
        CharacterAssignmentStore.Assignment second = createAccepted("separate-002");
        CharacterAssignmentJournalBridge.appendAccepted(first, journal);
        CharacterAssignmentJournalBridge.appendAccepted(second, journal);

        String firstThread = CharacterAssignmentJournalBridge.threadId(first.assignmentId);
        String secondThread = CharacterAssignmentJournalBridge.threadId(second.assignmentId);
        assertNotEquals(firstThread, secondThread);
        assertEquals(2, journal.listThreadIds().size());
        assertEquals("separate-001", journal.readThread(firstThread).get(0).assignmentId);
        assertEquals("separate-002", journal.readThread(secondThread).get(0).assignmentId);
    }

    private static int count(List<BuilderConversationProtocol.Turn> turns,
            BuilderConversationProtocol.TurnType type) {
        int result = 0;
        for (BuilderConversationProtocol.Turn turn : turns) if (turn.type == type) result++;
        return result;
    }
}
