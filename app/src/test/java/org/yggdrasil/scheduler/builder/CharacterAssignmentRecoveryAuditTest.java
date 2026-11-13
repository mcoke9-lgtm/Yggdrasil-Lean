package org.yggdrasil.scheduler.builder;

import org.junit.Test;
import static org.junit.Assert.*;

public class CharacterAssignmentRecoveryAuditTest {
    @Test public void canonicalFingerprintIgnoresObjectKeyOrder() {
        assertEquals(
                CharacterAssignmentRecoveryAudit.fingerprintJson("{\"b\":2,\"a\":1}"),
                CharacterAssignmentRecoveryAudit.fingerprintJson("{\"a\":1,\"b\":2}"));
    }

    @Test public void canonicalFingerprintNormalizesEquivalentNumberForms() {
        assertEquals(
                CharacterAssignmentRecoveryAudit.fingerprintJson("{\"value\":1.0}"),
                CharacterAssignmentRecoveryAudit.fingerprintJson("{\"value\":1}"));
    }

    @Test public void canonicalFingerprintPreservesArrayOrder() {
        assertNotEquals(
                CharacterAssignmentRecoveryAudit.fingerprintJson("[1,2]"),
                CharacterAssignmentRecoveryAudit.fingerprintJson("[2,1]"));
    }

    @Test(expected = IllegalArgumentException.class)
    public void malformedPayloadIsRejected() {
        CharacterAssignmentRecoveryAudit.fingerprintJson("{not-json}");
    }

    @Test public void multipleDistinctLedgerReportsRemainReviewable() {
        java.util.List<String> reports = java.util.Arrays.asList("sha-a", "sha-b");
        assertEquals("multiple-distinct-ledger-reports",
                CharacterAssignmentRecoveryAudit.auditFingerprint(reports, false, "ledger"));
    }

    @Test public void malformedJournalReportRemainsReviewableWithoutLedgerMatch() {
        assertEquals("unreadable-journal-report",
                CharacterAssignmentRecoveryAudit.auditFingerprint(
                        java.util.Collections.<String>emptyList(), true, "journal"));
    }

    @Test public void duplicateIdenticalReportsKeepTheirFingerprint() {
        java.util.List<String> reports = java.util.Arrays.asList("sha-a", "sha-a");
        assertEquals("sha-a",
                CharacterAssignmentRecoveryAudit.auditFingerprint(reports, false, "ledger"));
    }

    @Test(expected = IllegalArgumentException.class)
    public void emptyReportPayloadIsRejected() {
        CharacterAssignmentRecoveryAudit.fingerprintJson("   ");
    }

    @Test public void multipleDistinctLedgerReportsClassifyAsConflict() {
        String ledger = CharacterAssignmentRecoveryAudit.auditFingerprint(
                java.util.Arrays.asList("sha-a", "sha-b"), false, "ledger");
        CharacterAssignmentReconciliation.Finding finding =
                CharacterAssignmentReconciliation.returnReport("assignment-1", ledger, "sha-a");
        assertEquals(CharacterAssignmentReconciliation.State.CONFLICT, finding.state);
        assertTrue(finding.requiresReview());
    }

    @Test public void malformedJournalReportClassifiesAsJournalOnly() {
        String journal = CharacterAssignmentRecoveryAudit.auditFingerprint(
                java.util.Collections.<String>emptyList(), true, "journal");
        CharacterAssignmentReconciliation.Finding finding =
                CharacterAssignmentReconciliation.returnReport("assignment-1", null, journal);
        assertEquals(CharacterAssignmentReconciliation.State.JOURNAL_ONLY, finding.state);
        assertTrue(finding.requiresReview());
    }
}
