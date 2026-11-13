package org.yggdrasil.scheduler.builder;

import android.content.Context;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;

import static org.junit.Assert.*;

/** Contract and persistence tests for read-only cross-store reconciliation classification. */
@RunWith(RobolectricTestRunner.class)
public class CharacterAssignmentCrossStoreReconciliationTest {
    private static final String ASSIGNMENT_DB = CharacterAssignmentStore.DATABASE_NAME;
    private static final String JOURNAL_DB = "mdm-cross-store-reconciliation-test.db";
    private Context context;
    private CharacterAssignmentStore assignments;
    private BuilderConversationStore journal;

    @Before public void setUp() {
        context = RuntimeEnvironment.getApplication();
        context.deleteDatabase(ASSIGNMENT_DB);
        context.deleteDatabase(JOURNAL_DB);
        openStores();
    }

    @After public void tearDown() {
        closeStores();
        context.deleteDatabase(ASSIGNMENT_DB);
        context.deleteDatabase(JOURNAL_DB);
    }

    private void openStores() {
        assignments = new CharacterAssignmentStore(context);
        journal = new BuilderConversationStore(context, JOURNAL_DB);
    }

    private void closeStores() {
        if (journal != null) journal.close();
        if (assignments != null) assignments.close();
        journal = null;
        assignments = null;
    }

    private void reopenStores() {
        closeStores();
        openStores();
    }

    private CharacterAssignmentStore.Assignment createAssignment(String id) {
        assignments.create(id, "HUGINN", "ODIN", "Inspect " + id,
                "Read-only", "{\"repository_write\":false}",
                "{\"max_checks\":1}", "Persisted evidence");
        return assignments.get(id);
    }

    private CharacterAssignmentStore.Assignment createAccepted(String id) {
        assignments.create(id, "HUGINN", "ODIN", "Inspect " + id,
                "Read-only", "{\"repository_write\":false}",
                "{\"max_checks\":1}", "Persisted evidence");
        assignments.transition(id, CharacterAssignmentStore.Status.AUTHORIZED, "Týr authorization");
        assignments.transition(id, CharacterAssignmentStore.Status.QUEUED, "Queued");
        assignments.transition(id, CharacterAssignmentStore.Status.ACCEPTED, "Claimed");
        return assignments.get(id);
    }

    private boolean hasJournalTurn(String assignmentId, BuilderConversationProtocol.TurnType type) {
        String threadId = CharacterAssignmentJournalBridge.threadId(assignmentId);
        for (BuilderConversationProtocol.Turn turn : journal.readThread(threadId)) {
            if (turn.type == type) return true;
        }
        return false;
    }

    private static String fingerprint(String value) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8));
        StringBuilder result = new StringBuilder();
        for (byte b : digest) result.append(String.format(java.util.Locale.ROOT, "%02x", b & 0xff));
        return result.toString();
    }

    private static final class FailBeforeAcknowledgement implements BuilderConversationJournal {
        private final BuilderConversationJournal delegate;
        FailBeforeAcknowledgement(BuilderConversationJournal delegate) { this.delegate = delegate; }
        @Override public void append(BuilderConversationProtocol.Turn turn) {
            if (turn.type == BuilderConversationProtocol.TurnType.ACKNOWLEDGEMENT) {
                throw new IllegalStateException("Injected interruption");
            }
            delegate.append(turn);
        }
        @Override public List<BuilderConversationProtocol.Turn> readThread(String id) {
            return delegate.readThread(id);
        }
        @Override public List<String> listThreadIds() { return delegate.listThreadIds(); }
        @Override public JSONObject exportSnapshot() { return delegate.exportSnapshot(); }
        @Override public void close() { }
    }

    @Test public void ledgerAcceptanceWithoutJournalProjectionSurvivesReopenAsLedgerOnly() {
        createAccepted("ledger-only-acceptance");
        reopenStores();

        CharacterAssignmentStore.Assignment saved = assignments.get("ledger-only-acceptance");
        CharacterAssignmentReconciliation.Finding finding =
                CharacterAssignmentReconciliation.acceptance(saved.assignmentId,
                        saved.status == CharacterAssignmentStore.Status.ACCEPTED,
                        hasJournalTurn(saved.assignmentId, BuilderConversationProtocol.TurnType.CONTRIBUTION),
                        hasJournalTurn(saved.assignmentId, BuilderConversationProtocol.TurnType.ACKNOWLEDGEMENT));

        assertEquals(CharacterAssignmentReconciliation.State.LEDGER_ONLY, finding.state);
        assertTrue(finding.requiresReview());
        assertTrue(journal.readThread(CharacterAssignmentJournalBridge.threadId(saved.assignmentId)).isEmpty());
    }

    @Test public void partialJournalProjectionSurvivesReopenAsIncomplete() {
        CharacterAssignmentStore.Assignment accepted = createAccepted("partial-acceptance");
        try {
            CharacterAssignmentJournalBridge.appendAccepted(accepted,
                    new FailBeforeAcknowledgement(journal));
            fail("Expected injected interruption");
        } catch (IllegalStateException expected) {
            assertEquals("Injected interruption", expected.getMessage());
        }

        reopenStores();
        CharacterAssignmentStore.Assignment saved = assignments.get("partial-acceptance");
        CharacterAssignmentReconciliation.Finding finding =
                CharacterAssignmentReconciliation.acceptance(saved.assignmentId,
                        saved.status == CharacterAssignmentStore.Status.ACCEPTED,
                        hasJournalTurn(saved.assignmentId, BuilderConversationProtocol.TurnType.CONTRIBUTION),
                        hasJournalTurn(saved.assignmentId, BuilderConversationProtocol.TurnType.ACKNOWLEDGEMENT));

        assertEquals(CharacterAssignmentReconciliation.State.INCOMPLETE, finding.state);
        assertTrue(finding.requiresReview());
    }

    @Test public void journalAcceptanceWithoutLedgerAcceptanceSurvivesReopenAsJournalOnly() {
        CharacterAssignmentStore.Assignment created = createAssignment("journal-only-acceptance");
        CharacterAssignmentJournalBridge.appendAccepted(created, journal);
        reopenStores();

        CharacterAssignmentStore.Assignment saved = assignments.get("journal-only-acceptance");
        CharacterAssignmentReconciliation.Finding finding =
                CharacterAssignmentReconciliation.acceptance(saved.assignmentId,
                        saved.status == CharacterAssignmentStore.Status.ACCEPTED,
                        hasJournalTurn(saved.assignmentId, BuilderConversationProtocol.TurnType.CONTRIBUTION),
                        hasJournalTurn(saved.assignmentId, BuilderConversationProtocol.TurnType.ACKNOWLEDGEMENT));

        assertEquals(CharacterAssignmentReconciliation.State.JOURNAL_ONLY, finding.state);
        assertTrue(finding.requiresReview());
        assertEquals(CharacterAssignmentStore.Status.CREATED, saved.status);
    }

    @Test public void conflictingReturnReportsRemainUntouchedAndAreFlagged() throws Exception {
        CharacterAssignmentStore.Assignment accepted = createAccepted("conflicting-reports");
        String ledgerReport = "{\"outcome\":\"BLOCKED\",\"execution_started\":false}";
        String journalReport = "{\"outcome\":\"INTERRUPTED\",\"execution_started\":false}";
        assignments.returnReport(accepted.assignmentId, ledgerReport);
        CharacterAssignmentJournalBridge.appendReturnReport(accepted, journalReport, journal);
        reopenStores();

        CharacterAssignmentStore.Assignment saved = assignments.get("conflicting-reports");
        List<CharacterAssignmentStore.Payload> payloads = assignments.listPayloads(saved.assignmentId);
        List<BuilderConversationProtocol.Turn> turns =
                journal.readThread(CharacterAssignmentJournalBridge.threadId(saved.assignmentId));
        JSONObject journalEnvelope = null;
        for (BuilderConversationProtocol.Turn turn : turns) {
            if (turn.type == BuilderConversationProtocol.TurnType.RESPONSE) {
                journalEnvelope = new JSONObject(turn.body);
            }
        }
        assertNotNull(journalEnvelope);
        String persistedJournalReport = journalEnvelope.getJSONObject("report").toString();

        CharacterAssignmentReconciliation.Finding finding =
                CharacterAssignmentReconciliation.returnReport(saved.assignmentId,
                        fingerprint(payloads.get(0).payloadJson),
                        fingerprint(persistedJournalReport));

        assertEquals(CharacterAssignmentReconciliation.State.CONFLICT, finding.state);
        assertTrue(finding.requiresReview());
        assertEquals(ledgerReport, payloads.get(0).payloadJson);
        assertEquals("INTERRUPTED", journalEnvelope.getJSONObject("report").getString("outcome"));
    }

    @Test public void returnReportWithMatchingFingerprintsIsMatched() {
        CharacterAssignmentReconciliation.Finding finding =
                CharacterAssignmentReconciliation.returnReport("matching-report", "digest-001", "digest-001");
        assertEquals(CharacterAssignmentReconciliation.State.MATCHED, finding.state);
        assertFalse(finding.requiresReview());
    }

    @Test public void returnReportPresentOnlyInLedgerIsLedgerOnly() {
        CharacterAssignmentReconciliation.Finding finding =
                CharacterAssignmentReconciliation.returnReport("ledger-report", "digest-ledger", null);
        assertEquals(CharacterAssignmentReconciliation.State.LEDGER_ONLY, finding.state);
        assertTrue(finding.requiresReview());
    }

    @Test public void returnReportPresentOnlyInJournalIsJournalOnly() {
        CharacterAssignmentReconciliation.Finding finding =
                CharacterAssignmentReconciliation.returnReport("journal-report", null, "digest-journal");
        assertEquals(CharacterAssignmentReconciliation.State.JOURNAL_ONLY, finding.state);
        assertTrue(finding.requiresReview());
    }

    @Test public void acceptanceAcknowledgementWithoutRequestIsConflict() {
        CharacterAssignmentReconciliation.Finding finding =
                CharacterAssignmentReconciliation.acceptance("orphan-ack", true, false, true);
        assertEquals(CharacterAssignmentReconciliation.State.CONFLICT, finding.state);
        assertTrue(finding.requiresReview());
    }

    @Test public void absenceOnBothSidesIsNotTreatedAsAConflict() {
        CharacterAssignmentReconciliation.Finding acceptance =
                CharacterAssignmentReconciliation.acceptance("no-acceptance", false, false, false);
        CharacterAssignmentReconciliation.Finding report =
                CharacterAssignmentReconciliation.returnReport("no-report", null, null);
        assertEquals(CharacterAssignmentReconciliation.State.NO_EVIDENCE, acceptance.state);
        assertEquals(CharacterAssignmentReconciliation.State.NO_EVIDENCE, report.state);
        assertFalse(acceptance.requiresReview());
        assertFalse(report.requiresReview());
    }

    @Test(expected = IllegalArgumentException.class)
    public void blankAssignmentIdIsRejected() {
        CharacterAssignmentReconciliation.acceptance(" ", false, false, false);
    }

    @Test(expected = IllegalArgumentException.class)
    public void blankFingerprintIsRejected() {
        CharacterAssignmentReconciliation.returnReport("bad-fingerprint", " ", null);
    }
}
