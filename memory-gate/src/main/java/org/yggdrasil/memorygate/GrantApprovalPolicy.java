package org.yggdrasil.memorygate;

import java.util.Collections;
import java.util.UUID;

/** Builds only narrow, short-lived grants after an explicit local approval. */
final class GrantApprovalPolicy {
    static final long MAX_DURATION_MILLIS = 15L * 60L * 1000L;

    static MemoryAccessGrant create(
            String recordId, String objective, String purpose,
            long nowMillis, long requestedExpiryMillis) {
        if (!"project-fact-001".equals(recordId)
                && !"decision-history-001".equals(recordId)
                && !"restricted-test-001".equals(recordId)) {
            return null;
        }
        if (isBlank(objective) || isBlank(purpose)) return null;
        if (requestedExpiryMillis <= nowMillis
                || requestedExpiryMillis - nowMillis > MAX_DURATION_MILLIS) {
            return null;
        }

        return new MemoryAccessGrant(
                UUID.randomUUID().toString(),
                objective.trim(),
                purpose.trim(),
                Collections.singleton(recordId),
                requestedExpiryMillis,
                requestedExpiryMillis);
    }

    private static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    private GrantApprovalPolicy() { }
}
