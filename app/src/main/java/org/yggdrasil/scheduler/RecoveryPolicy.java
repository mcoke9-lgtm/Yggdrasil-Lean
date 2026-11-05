package org.yggdrasil.scheduler;

/**
 * Deterministic recovery rules for polling GitHub Actions after process or network
 * interruptions. GitHub run IDs are monotonic within a repository, so the durable
 * cursor is safer than comparing timestamps that may be equal or arrive late.
 */
public final class RecoveryPolicy {
    private RecoveryPolicy() {}

    /** Only a completed run newer than the durable cursor is eligible for processing. */
    public static boolean isUnseenCompletedRun(long lastSeenRunId, long runId,
                                                String status) {
        return runId > 0 && runId > lastSeenRunId && "completed".equals(status);
    }

    /** Manual checks must never consume the scheduled local-date opportunity. */
    public static String scheduledDateAfterCheck(boolean manual, String lastScheduledDate,
                                                  String today) {
        if (manual) return lastScheduledDate;
        if (today == null || today.trim().isEmpty())
            throw new IllegalArgumentException("today must not be blank");
        return today;
    }

    /** A full page is ambiguous: there may be unseen runs beyond the returned page. */
    public static boolean scanIsComplete(int returnedCount, int pageSize) {
        if (returnedCount < 0 || pageSize < 1)
            throw new IllegalArgumentException("Invalid pagination counts");
        return returnedCount < pageSize;
    }

    /** A transient failure remains eligible for retry; no cursor/date may advance. */
    public static boolean shouldRetryAfterFailure(boolean networkAvailable,
                                                    boolean transientFailure) {
        return networkAvailable && transientFailure;
    }
}
