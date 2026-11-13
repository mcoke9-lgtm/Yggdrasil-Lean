package org.yggdrasil.scheduler.builder;

/**
 * Read-only classification rules for evidence split across the assignment ledger and
 * Character conversation journal.
 *
 * This class deliberately performs no repair, append, transition, or deletion. A caller
 * must preserve both stores and obtain authorization before any future reconciliation action.
 */
public final class CharacterAssignmentReconciliation {
    public enum Kind { ACCEPTANCE, RETURN_REPORT }

    public enum State {
        NO_EVIDENCE,
        MATCHED,
        LEDGER_ONLY,
        JOURNAL_ONLY,
        INCOMPLETE,
        CONFLICT
    }

    public static final class Finding {
        public final String assignmentId;
        public final Kind kind;
        public final State state;

        private Finding(String assignmentId, Kind kind, State state) {
            this.assignmentId = assignmentId;
            this.kind = kind;
            this.state = state;
        }

        /** Every discrepancy requires review; classification never authorizes repair. */
        public boolean requiresReview() {
            return state != State.MATCHED && state != State.NO_EVIDENCE;
        }
    }

    private CharacterAssignmentReconciliation() {}

    /**
     * Classify the acceptance projection.
     *
     * A complete journal acceptance consists of both the original request and the
     * Character acknowledgement. Acknowledgement without request is contradictory.
     */
    public static Finding acceptance(String assignmentId, boolean ledgerAccepted,
            boolean journalRequestPresent, boolean journalAcknowledgementPresent) {
        requireAssignmentId(assignmentId);

        State state;
        if (journalAcknowledgementPresent && !journalRequestPresent) {
            state = State.CONFLICT;
        } else if (ledgerAccepted) {
            if (journalRequestPresent && journalAcknowledgementPresent) {
                state = State.MATCHED;
            } else if (journalRequestPresent) {
                state = State.INCOMPLETE;
            } else {
                state = State.LEDGER_ONLY;
            }
        } else if (!journalRequestPresent) {
            state = State.NO_EVIDENCE;
        } else if (journalAcknowledgementPresent) {
            state = State.JOURNAL_ONLY;
        } else {
            state = State.INCOMPLETE;
        }
        return new Finding(assignmentId, Kind.ACCEPTANCE, state);
    }

    /**
     * Classify a return-report projection using caller-supplied fingerprints.
     *
     * Fingerprints must be computed from a deterministic canonical representation of
     * the report JSON, not from timestamps or the enclosing journal turn.
     */
    public static Finding returnReport(String assignmentId, String ledgerFingerprint,
            String journalFingerprint) {
        requireAssignmentId(assignmentId);
        requireFingerprint(ledgerFingerprint, "ledgerFingerprint");
        requireFingerprint(journalFingerprint, "journalFingerprint");

        boolean ledgerPresent = ledgerFingerprint != null;
        boolean journalPresent = journalFingerprint != null;
        State state;
        if (!ledgerPresent && !journalPresent) {
            state = State.NO_EVIDENCE;
        } else if (ledgerPresent && !journalPresent) {
            state = State.LEDGER_ONLY;
        } else if (!ledgerPresent) {
            state = State.JOURNAL_ONLY;
        } else if (ledgerFingerprint.equals(journalFingerprint)) {
            state = State.MATCHED;
        } else {
            state = State.CONFLICT;
        }
        return new Finding(assignmentId, Kind.RETURN_REPORT, state);
    }

    private static void requireAssignmentId(String assignmentId) {
        if (assignmentId == null || assignmentId.trim().isEmpty()) {
            throw new IllegalArgumentException("assignmentId must not be blank");
        }
    }

    private static void requireFingerprint(String value, String field) {
        if (value != null && value.trim().isEmpty()) {
            throw new IllegalArgumentException(field + " must be null or nonblank");
        }
    }
}
