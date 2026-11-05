package org.yggdrasil.scheduler;

import static org.junit.Assert.*;
import org.junit.Test;

public class RecoveryPolicyTest {
    @Test public void onlyCompletedRunsBeyondCursorAreNew() {
        assertTrue(RecoveryPolicy.isUnseenCompletedRun(100, 101, "completed"));
        assertFalse(RecoveryPolicy.isUnseenCompletedRun(100, 100, "completed"));
        assertFalse(RecoveryPolicy.isUnseenCompletedRun(100, 99, "completed"));
        assertFalse(RecoveryPolicy.isUnseenCompletedRun(100, 102, "in_progress"));
        assertFalse(RecoveryPolicy.isUnseenCompletedRun(100, 0, "completed"));
    }

    @Test public void manualCheckDoesNotConsumeScheduledDate() {
        assertEquals("2026-11-04", RecoveryPolicy.scheduledDateAfterCheck(
                true, "2026-11-04", "2026-11-05"));
    }

    @Test public void scheduledCheckAdvancesLocalDate() {
        assertEquals("2026-11-05", RecoveryPolicy.scheduledDateAfterCheck(
                false, "2026-11-04", "2026-11-05"));
    }

    @Test public void fullPageIsNotTreatedAsCompleteScan() {
        assertFalse(RecoveryPolicy.scanIsComplete(100, 100));
        assertTrue(RecoveryPolicy.scanIsComplete(99, 100));
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsInvalidPaginationCounts() {
        RecoveryPolicy.scanIsComplete(-1, 100);
    }

    @Test public void transientNetworkFailureIsRetryable() {
        assertTrue(RecoveryPolicy.shouldRetryAfterFailure(true, true));
        assertFalse(RecoveryPolicy.shouldRetryAfterFailure(false, true));
        assertFalse(RecoveryPolicy.shouldRetryAfterFailure(true, false));
    }
}
