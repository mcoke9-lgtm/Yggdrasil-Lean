package org.yggdrasil.scheduler.builder;

import org.junit.Test;
import static org.junit.Assert.*;

public class CharacterAssignmentRecoveryPlanTest {
    @Test public void matchedEvidenceNeedsNoRecoveryAction() {
        CharacterAssignmentReconciliation.Finding finding =
                CharacterAssignmentReconciliation.acceptance("matched", true, true, true);
        CharacterAssignmentRecoveryPlan.Plan plan = CharacterAssignmentRecoveryPlan.from(finding);
        assertEquals(CharacterAssignmentRecoveryPlan.Action.NO_ACTION, plan.action);
        assertFalse(plan.requiresHumanReview);
        assertFalse(plan.requiresTyrAuthorization);
        assertFalse(plan.executionPermitted);
    }

    @Test public void ledgerOnlyAcceptanceProposesReplayButDoesNotAuthorizeIt() {
        CharacterAssignmentReconciliation.Finding finding =
                CharacterAssignmentReconciliation.acceptance("ledger-only", true, false, false);
        CharacterAssignmentRecoveryPlan.Plan plan = CharacterAssignmentRecoveryPlan.from(finding);
        assertEquals(CharacterAssignmentRecoveryPlan.Action.PROPOSE_ACCEPTANCE_PROJECTION_REPLAY, plan.action);
        assertTrue(plan.requiresHumanReview);
        assertTrue(plan.requiresTyrAuthorization);
        assertFalse(plan.executionPermitted);
        assertEquals("ledger-only", plan.assignmentId);
    }

    @Test public void incompleteAcceptanceProposesReplayButDoesNotAuthorizeIt() {
        CharacterAssignmentReconciliation.Finding finding =
                CharacterAssignmentReconciliation.acceptance("incomplete", true, true, false);
        CharacterAssignmentRecoveryPlan.Plan plan = CharacterAssignmentRecoveryPlan.from(finding);
        assertEquals(CharacterAssignmentRecoveryPlan.Action.PROPOSE_ACCEPTANCE_PROJECTION_REPLAY, plan.action);
        assertTrue(plan.requiresTyrAuthorization);
        assertFalse(plan.executionPermitted);
    }

    @Test public void journalOnlyEvidenceNeverProposesLedgerMutation() {
        CharacterAssignmentReconciliation.Finding finding =
                CharacterAssignmentReconciliation.acceptance("journal-only", false, true, true);
        CharacterAssignmentRecoveryPlan.Plan plan = CharacterAssignmentRecoveryPlan.from(finding);
        assertEquals(CharacterAssignmentRecoveryPlan.Action.REVIEW_WITHOUT_MUTATION, plan.action);
        assertTrue(plan.requiresHumanReview);
        assertFalse(plan.requiresTyrAuthorization);
        assertFalse(plan.executionPermitted);
    }

    @Test public void conflictingEvidenceIsPreservedAndEscalated() {
        CharacterAssignmentReconciliation.Finding finding =
                CharacterAssignmentReconciliation.returnReport("conflict", "ledger-a", "journal-b");
        CharacterAssignmentRecoveryPlan.Plan plan = CharacterAssignmentRecoveryPlan.from(finding);
        assertEquals(CharacterAssignmentRecoveryPlan.Action.PRESERVE_CONFLICT, plan.action);
        assertTrue(plan.requiresHumanReview);
        assertFalse(plan.executionPermitted);
    }

    @Test public void returnReportLedgerOnlyRemainsReviewOnly() {
        CharacterAssignmentReconciliation.Finding finding =
                CharacterAssignmentReconciliation.returnReport("report-ledger-only", "digest", null);
        CharacterAssignmentRecoveryPlan.Plan plan = CharacterAssignmentRecoveryPlan.from(finding);
        assertEquals(CharacterAssignmentRecoveryPlan.Action.REVIEW_WITHOUT_MUTATION, plan.action);
        assertFalse(plan.executionPermitted);
    }

    @Test(expected = IllegalArgumentException.class)
    public void nullFindingIsRejected() {
        CharacterAssignmentRecoveryPlan.from(null);
    }
}
