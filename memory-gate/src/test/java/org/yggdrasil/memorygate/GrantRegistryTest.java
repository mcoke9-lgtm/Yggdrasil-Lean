package org.yggdrasil.memorygate;

import android.content.Context;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

import java.util.Collections;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
public class GrantRegistryTest {
    private Context context() { return RuntimeEnvironment.getApplication(); }

    @Test public void tokenIsBoundToApprovedUid() {
        GrantRegistry registry = new GrantRegistry(context());
        String token = registry.issueForApprovedCaller(12001,
                grant("record-a", System.currentTimeMillis() + 60_000));
        assertNotNull(token);
        assertNull(registry.resolve(token, 12002, "record-a", System.currentTimeMillis()));
        assertNotNull(registry.resolve(token, 12001, "record-a", System.currentTimeMillis()));
    }

    @Test public void tokenCannotExpandRecordScope() {
        GrantRegistry registry = new GrantRegistry(context());
        String token = registry.issueForApprovedCaller(12001,
                grant("record-a", System.currentTimeMillis() + 60_000));
        assertNull(registry.resolve(token, 12001, "record-b", System.currentTimeMillis()));
    }

    @Test public void expiredGrantIsRejectedAndRemoved() {
        GrantRegistry registry = new GrantRegistry(context());
        String token = registry.issueForApprovedCaller(12001,
                grant("record-a", System.currentTimeMillis() + 60_000));
        assertNull(registry.resolve(token, 12001, "record-a", System.currentTimeMillis() + 120_000));
        assertNull(registry.resolve(token, 12001, "record-a", System.currentTimeMillis()));
    }

    @Test public void onlyOwningUidCanClaimApprovedToken() {
        GrantRegistry registry = new GrantRegistry(context());
        String token = registry.issueForApprovedCaller(12001,
                grant("record-a", System.currentTimeMillis() + 60_000));
        assertNull(registry.claimForUid(12002, System.currentTimeMillis()));
        assertEquals(token, registry.claimForUid(12001, System.currentTimeMillis()));
    }

    @Test public void ambiguousClaimsFailClosed() {
        GrantRegistry registry = new GrantRegistry(context());
        long expiry = System.currentTimeMillis() + 60_000;
        registry.issueForApprovedCaller(12001, grant("record-a", expiry));
        registry.issueForApprovedCaller(12001, grant("record-b", expiry));
        assertNull(registry.claimForUid(12001, System.currentTimeMillis()));
    }

    @Test public void revokedTokenIsRejected() {
        GrantRegistry registry = new GrantRegistry(context());
        String token = registry.issueForApprovedCaller(12001,
                grant("record-a", System.currentTimeMillis() + 60_000));
        registry.revoke(token);
        assertNull(registry.resolve(token, 12001, "record-a", System.currentTimeMillis()));
    }

    @Test public void grantSurvivesRegistryRecreation() {
        long now = System.currentTimeMillis();
        GrantRegistry first = new GrantRegistry(context());
        String token = first.issueForApprovedCaller(12001, grant("record-a", now + 600_000));
        assertNotNull(token);
        first.close();

        GrantRegistry recreated = new GrantRegistry(context());
        assertEquals(token, recreated.claimForUid(12001, now + 1));
        assertNotNull(recreated.resolve(token, 12001, "record-a", now + 2));
        recreated.close();
    }

    private static MemoryAccessGrant grant(String recordId, long expiry) {
        return new MemoryAccessGrant("request-1", "test objective", "test purpose",
                Collections.singleton(recordId), expiry, expiry);
    }
}
