package org.yggdrasil.memorygate;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * In-process grant registry. Tokens are opaque handles; clients never submit
 * grant contents. Each handle is bound to the Android UID that was approved.
 *
 * Grant issuance is package-internal and must only be called by a future
 * authenticated approval component. No exported issuance endpoint exists.
 */
final class GrantRegistry {
    private static final class Entry {
        final int ownerUid;
        final MemoryAccessGrant grant;

        Entry(int ownerUid, MemoryAccessGrant grant) {
            this.ownerUid = ownerUid;
            this.grant = grant;
        }
    }

    private final Map<String, Entry> entries = new HashMap<>();

    synchronized String issueForApprovedCaller(int ownerUid, MemoryAccessGrant grant) {
        if (ownerUid < 0 || grant == null) return null;
        if (AuthorizationDecision.evaluate(grant,
                grant.permittedRecordIds.isEmpty() ? null
                        : grant.permittedRecordIds.iterator().next(),
                System.currentTimeMillis()) != AuthorizationDecision.Outcome.ALLOW) {
            return null;
        }
        String token = UUID.randomUUID().toString();
        entries.put(token, new Entry(ownerUid, grant));
        return token;
    }

    synchronized MemoryAccessGrant resolve(
            String token, int callingUid, String requestedRecordId, long nowMillis) {
        if (token == null || callingUid < 0 || requestedRecordId == null) return null;
        Entry entry = entries.get(token);
        if (entry == null || entry.ownerUid != callingUid) return null;
        if (AuthorizationDecision.evaluate(entry.grant,
                requestedRecordId, nowMillis) != AuthorizationDecision.Outcome.ALLOW) {
            entries.remove(token);
            return null;
        }
        return entry.grant;
    }

    synchronized void revoke(String token) {
        if (token != null) entries.remove(token);
    }

    private GrantRegistry() { }
}
