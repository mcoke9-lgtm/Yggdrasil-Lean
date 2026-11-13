package org.yggdrasil.scheduler.builder;

import static org.junit.Assert.*;
import android.content.Context;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import java.util.List;

/** Idempotency and provenance tests for the assignment-to-journal handoff. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35)
public class CharacterAssignmentJournalBridgeTest {
    private static final String ASSIGNMENT_DB = CharacterAssignmentStore.DATABASE_NAME;
    private static final String JOURNAL_DB = "mdm-journal-bridge-test.db";
    private Context context;
    private CharacterAssignmentStore assignments;
    private BuilderConversationStore journal;

    @Before public void setUp() {
        context = org.robolectric.RuntimeEnvironment.getApplication();
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

    private CharacterAssignmentStore.Assignment acceptedAssignment() {
        assignments.create("bridge-001", "HUGINN", "ODIN", "Observe PR",
                "Read-only", "{\"repository_write\":false}", "{\"max_checks\":1}", "CI result");
        assignments.transition("bridge-001", CharacterAssignmentStore.Status.AUTHORIZED,
                "Týr authorization");
        assignments.transition("bridge-001", CharacterAssignmentStore.Status.QUEUED, "Queued");
        assignments.transition("bridge-001", CharacterAssignmentStore.Status.ACCEPTED, "Claimed");
        return assignments.get("bridge-001");
    }

    @Test public void acceptanceAndReturnReportReachAssignmentThread() throws Exception {
        CharacterAssignmentStore.Assignment assignment = acceptedAssignment();
        JSONObject report = new JSONObject();
        report.put("outcome", "BLOCKED");
        report.put("reason_code", "NO_EXECUTION_ADAPTER");
        report.put("execution_started", false);

        CharacterAssignmentJournalBridge.appendAccepted(assignment, journal);
        CharacterAssignmentJournalBridge.appendReturnReport(assignment, report.toString(), journal);

        List<BuilderConversationProtocol.Turn> turns =
                journal.readThread(CharacterAssignmentJournalBridge.threadId("bridge-001"));
        assertEquals(3, turns.size());
        assertEquals(BuilderConversationProtocol.Participant.ODIN, turns.get(0).speaker);
        assertEquals(BuilderConversationProtocol.TurnType.CONTRIBUTION, turns.get(0).type);
        assertEquals("bridge-001", turns.get(0).assignmentId);
        assertEquals(BuilderConversationProtocol.Participant.HUGINN, turns.get(1).speaker);
        assertEquals(BuilderConversationProtocol.TurnType.ACKNOWLEDGEMENT, turns.get(1).type);
        assertEquals(BuilderConversationProtocol.Participant.ODIN, turns.get(1).recipient);
        assertEquals(turns.get(0).turnId, turns.get(1).replyToTurnId);
        assertTrue(turns.get(1).body.contains("\"event\":\"ACCEPTED\""));
        assertEquals(BuilderConversationProtocol.Participant.HUGINN, turns.get(2).speaker);
        assertEquals(BuilderConversationProtocol.TurnType.RESPONSE, turns.get(2).type);
        assertEquals(BuilderConversationProtocol.Participant.ODIN, turns.get(2).recipient);
        assertEquals(turns.get(0).turnId, turns.get(2).replyToTurnId);
        assertEquals("bridge-001", turns.get(2).assignmentId);
        assertTrue(turns.get(2).body.contains("\"NO_EXECUTION_ADAPTER\""));
        assertTrue(turns.get(2).body.contains("\"execution_started\":false"));
    }

    @Test public void repeatedHandoffDoesNotDuplicateTurns() {
        CharacterAssignmentStore.Assignment assignment = acceptedAssignment();
        String report = "{\"outcome\":\"BLOCKED\",\"execution_started\":false}";
        CharacterAssignmentJournalBridge.appendAccepted(assignment, journal);
        CharacterAssignmentJournalBridge.appendAccepted(assignment, journal);
        CharacterAssignmentJournalBridge.appendReturnReport(assignment, report, journal);
        CharacterAssignmentJournalBridge.appendReturnReport(assignment, report, journal);
        assertEquals(3, journal.readThread(
                CharacterAssignmentJournalBridge.threadId("bridge-001")).size());
    }

    @Test public void assignmentsUseSeparateJournalThreads() {
        CharacterAssignmentStore.Assignment first = acceptedAssignment();
        assertNotEquals(CharacterAssignmentJournalBridge.threadId(first.assignmentId),
                CharacterAssignmentJournalBridge.threadId("another-assignment"));
    }
}
