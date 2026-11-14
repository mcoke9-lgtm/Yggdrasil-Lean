package org.yggdrasil.memorygate;

import org.junit.Test;

import java.util.Collections;

import static org.junit.Assert.*;

public class GrantRegistryTest {
    @Test
    public void tokenIsBoundToApprovedUid() {
        GrantRegistry registry = new GrantRegistry();
        MemoryAccessGrant grant = grant("record-a", System.currentTimeMillis() + 60_000);
        String token = registry.issueForApprovedCaller(12001, grant);

        assertNotNull(token);
        assertNull(registry.resolve(token, 12002, "record-a", System.currentTimeMillis()));
        assertNotNull(registry.resolve(token, 12001, "record-a", System.currentTimeMillis()));
    }

    @Test
    public void tokenCannotExpandRecordScope() {
        GrantRegistry registry = new GrantRegistry();
        String token = registry.issueForApprovedCaller(
                12001, grant("record-a", System.currentTimeMillis() + 60_000));

        assertNull(registry.resolve(token, 12001, "record-b", System.currentTimeMillis()));
    }

    @Test
    public void expiredGrantIsRejectedAndRemoved() {
        GrantRegistry registry = new GrantRegistry();
        String token = registry.issueForApprovedCaller(
                12001, grant("record-a", System.currentTimeMillis() + 60_000));

        assertNull(registry.resolve(token, 12001, "record-a",
                System.currentTimeMillis() + 120_000));
        assertNull(registry.resolve(token, 12001, "record-a", System.currentTimeMillis()));
    }

    @Test
    public void onlyOwningUidCanClaimApprovedToken() {
        GrantRegistry registry = new GrantRegistry();
        String token = registry.issueForApprovedCaller(
                12001, grant("record-a", System.currentTimeMillis() + 60_000));

        assertNull(registry.claimForUid(12002, System.currentTimeMillis()));
        assertEquals(token, registry.claimForUid(12001, System.currentTimeMillis()));
    }

    @Test
    public void ambiguousClaimsFailClosed() {
        GrantRegistry registry = new GrantRegistry();
        long expiry = System.currentTimeMillis() + 60_000;
        registry.issueForApprovedCaller(12001, grant("record-a", expiry));
        registry.issueForApprovedCaller(12001, grant("record-b", expiry));

        assertNull(registry.claimForUid(12001, System.currentTimeMillis()));
    }

    @Test
    public void revokedTokenIsRejected() {
        GrantRegistry registry = new GrantRegistry();
        String token = registry.issueForApprovedCaller(
                12001, grant("record-a", System.currentTimeMillis() + 60_000));
        registry.revoke(token);

        assertNull(registry.resolve(token, 12001, "record-a", System.currentTimeMillis()));
    }

    private static MemoryAccessGrant grant(String recordId, long expiry) {
        return new MemoryAccessGrant("request-1", "test objective", "test purpose",
                Collections.singleton(recordId), expiry, expiry);
    }
}
