package org.yggdrasil.memorygate;

import org.junit.Test;

import static org.junit.Assert.*;

public class GrantApprovalPolicyTest {
    @Test
    public void createsSingleRecordShortLivedGrant() {
        long now = 1_000_000L;
        MemoryAccessGrant grant = GrantApprovalPolicy.create(
                "project-fact-001", "Validate retrieval", "Prototype test",
                now, now + 10_000L);

        assertNotNull(grant);
        assertEquals(1, grant.permittedRecordIds.size());
        assertTrue(grant.permittedRecordIds.contains("project-fact-001"));
        assertEquals(now + 10_000L, grant.expiresAtMillis);
        assertEquals(grant.expiresAtMillis, grant.stopAtMillis);
    }

    @Test
    public void rejectsUnknownRecord() {
        assertNull(GrantApprovalPolicy.create("all-records", "Objective", "Purpose",
                1_000L, 2_000L));
    }

    @Test
    public void rejectsBlankObjectiveOrPurpose() {
        assertNull(GrantApprovalPolicy.create("project-fact-001", " ", "Purpose",
                1_000L, 2_000L));
        assertNull(GrantApprovalPolicy.create("project-fact-001", "Objective", "",
                1_000L, 2_000L));
    }

    @Test
    public void rejectsExpiredAndOverlongRequests() {
        long now = 1_000_000L;
        assertNull(GrantApprovalPolicy.create("project-fact-001", "Objective", "Purpose",
                now, now));
        assertNull(GrantApprovalPolicy.create("project-fact-001", "Objective", "Purpose",
                now, now + GrantApprovalPolicy.MAX_DURATION_MILLIS + 1L));
    }
}
