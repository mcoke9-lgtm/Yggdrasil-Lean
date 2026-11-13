package org.yggdrasil.scheduler.builder;

/**
 * Read-only recovery planning for cross-store assignment evidence.
 *
 * A plan is advisory only. It never writes either store, advances lifecycle, or
 * represents Týr authorization. Any future mutation must pass a separate,
 * assignment-scoped authorization gate.
 */
public final class CharacterAssignmentRecoveryPlan {
    public enum Action {
        NO_ACTION,
        PROPOSE_ACCEPTANCE_PROJECTION_REPLAY,
        REVIEW_WITHOUT_MUTATION,
        PRESERVE_CONFLICT
    }

    public static final class Plan {
        public final String assignmentId;
        public final CharacterAssignmentReconciliation.Kind kind;
        public final CharacterAssignmentReconciliation.State finding;
        public final Action action;
        public final boolean requiresHumanReview;
        public final boolean requiresTyrAuthorization;
        public final boolean executionPermitted;

        private Plan(CharacterAssignmentReconciliation.Finding source, Action action,
                boolean requiresHumanReview, boolean requiresTyrAuthorization) {
            assignmentId = source.assignmentId;
            kind = source.kind;
            finding = source.state;
            this.action = action;
            this.requiresHumanReview = requiresHumanReview;
            this.requiresTyrAuthorization = requiresTyrAuthorization;
            executionPermitted = false;
        }
    }

    private CharacterAssignmentRecoveryPlan() {}

    /** Produce a non-executable recommendation from one finding. */
    public static Plan from(CharacterAssignmentReconciliation.Finding finding) {
        if (finding == null) throw new IllegalArgumentException("finding is required");

        if (finding.state == CharacterAssignmentReconciliation.State.NO_EVIDENCE
                || finding.state == CharacterAssignmentReconciliation.State.MATCHED) {
            return new Plan(finding, Action.NO_ACTION, false, false);
        }

        if (finding.state == CharacterAssignmentReconciliation.State.CONFLICT) {
            return new Plan(finding, Action.PRESERVE_CONFLICT, true, false);
        }

        if (finding.kind == CharacterAssignmentReconciliation.Kind.ACCEPTANCE
                && (finding.state == CharacterAssignmentReconciliation.State.LEDGER_ONLY
                || finding.state == CharacterAssignmentReconciliation.State.INCOMPLETE)) {
            return new Plan(finding, Action.PROPOSE_ACCEPTANCE_PROJECTION_REPLAY,
                    true, true);
        }

        // Journal-only evidence must not create or advance ledger state. Return-report
        // projection is held for review until a dedicated, idempotent repair contract exists.
        return new Plan(finding, Action.REVIEW_WITHOUT_MUTATION, true, false);
    }
}
