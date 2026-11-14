package org.yggdrasil.memorygate;

/** Deterministic default-deny evaluation for a single requested memory record. */
final class AuthorizationDecision {
    enum Outcome { ALLOW, DENY }

    static Outcome evaluate(MemoryAccessGrant grant, String recordId, long nowMillis) {
        if (grant == null || isBlank(grant.requestId) || isBlank(grant.objective)
                || isBlank(grant.purpose) || isBlank(recordId)) {
            return Outcome.DENY;
        }
        if (grant.permittedRecordIds.isEmpty()
                || !grant.permittedRecordIds.contains(recordId)) {
            return Outcome.DENY;
        }
        if (grant.expiresAtMillis <= nowMillis || grant.stopAtMillis <= nowMillis) {
            return Outcome.DENY;
        }
        return Outcome.ALLOW;
    }

    private static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    private AuthorizationDecision() { }
}
