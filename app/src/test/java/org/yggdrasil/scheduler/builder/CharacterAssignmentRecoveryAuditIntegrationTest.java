package org.yggdrasil.scheduler.builder;

import static org.junit.Assert.*;

import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

/** End-to-end read-only audit checks across the assignment and conversation SQLite stores. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35)
public class CharacterAssignmentRecoveryAuditIntegrationTest {
    private static final String JOURNAL_DB = "mdm-recovery-audit-integration.db";
    private Context context;
    private CharacterAssignmentStore ledger;
    private BuilderConversationStore journal;

    @Before public void setUp() {
        context = org.robolectric.RuntimeEnvironment.getApplication();
        context.deleteDatabase(CharacterAssignmentStore.DATABASE_NAME);
        context.deleteDatabase(JOURNAL_DB);
        ledger = new CharacterAssignmentStore(context);
        journal = new BuilderConversationStore(context, JOURNAL_DB);
    }

    @After public void tearDown() {
        if (journal != null) journal.close();
        if (ledger != null) ledger.close();
        context.deleteDatabase(CharacterAssignmentStore.DATABASE_NAME);
        context.deleteDatabase(JOURNAL_DB);
    }

    @Test public void scanMatchesBothStoresAndDoesNotMutateEitherDatabase() throws Exception {
        ledger.create("audit-001", "HUGINN", "ODIN", "Inspect evidence",
                "Read-only audit", "{\\"repository_write\\":false}",
                "{\\"max_checks\\":1}", "Return report");
        ledger.transition("audit-001", CharacterAssignmentStore.Status.AUTHORIZED, "Tyr authorized");
        ledger.transition("audit-001", CharacterAssignmentStore.Status.QUEUED, "Queued");
        ledger.transition("audit-001", CharacterAssignmentStore.Status.ACCEPTED, "Huginn accepted");

        CharacterAssignmentStore.Assignment assignment = ledger.get("audit-001");
        CharacterAssignmentJournalBridge.appendAccepted(assignment, journal);
        JSONObject report = new JSONObject();
        report.put("outcome", "BLOCKED");
        report.put("reason_code", "NO_EXECUTION_ADAPTER");
        report.put("execution_started", false);
        ledger.returnReport("audit-001", report.toString());
        CharacterAssignmentJournalBridge.appendReturnReport(assignment, report.toString(), journal);

        String ledgerBefore = snapshot(ledger.getReadableDatabase(),
                "assignments", "assignment_events", "assignment_payloads");
        String journalBefore = snapshot(journal.getReadableDatabase(), "conversation_turns");

        CharacterAssignmentRecoveryAudit.Report result =
                CharacterAssignmentRecoveryAudit.scan(ledger, journal);

        String ledgerAfter = snapshot(ledger.getReadableDatabase(),
                "assignments", "assignment_events", "assignment_payloads");
        String journalAfter = snapshot(journal.getReadableDatabase(), "conversation_turns");

        assertEquals(ledgerBefore, ledgerAfter);
        assertEquals(journalBefore, journalAfter);
        assertEquals(1, result.assignmentsScanned);
        assertEquals(2, result.findingCount());
        assertEquals(0, result.discrepancyCount());
        assertEquals(0, result.discrepancyFindingCount());
        assertEquals(CharacterAssignmentReconciliation.State.MATCHED,
                result.assessments.get(0).finding.state);
        assertEquals(CharacterAssignmentReconciliation.State.MATCHED,
                result.assessments.get(1).finding.state);
        assertFalse(result.assessments.get(0).plan.executionPermitted);
        assertFalse(result.assessments.get(1).plan.executionPermitted);
    }

    private static String snapshot(SQLiteDatabase db, String... tables) {
        StringBuilder snapshot = new StringBuilder();
        for (String table : tables) {
            snapshot.append(table).append('\\n');
            try (Cursor cursor = db.rawQuery("SELECT * FROM " + table
                    + " ORDER BY 1", null)) {
                int columns = cursor.getColumnCount();
                while (cursor.moveToNext()) {
                    for (int i = 0; i < columns; i++) {
                        if (i > 0) snapshot.append('|');
                        snapshot.append(cursor.isNull(i) ? "<NULL>" : cursor.getString(i));
                    }
                    snapshot.append('\\n');
                }
            }
        }
        return snapshot.toString();
    }
}
