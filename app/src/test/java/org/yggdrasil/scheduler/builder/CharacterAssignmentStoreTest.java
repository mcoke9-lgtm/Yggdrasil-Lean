package org.yggdrasil.scheduler.builder;

import static org.junit.Assert.*;
import android.content.Context;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

/** Persistence and lifecycle contract tests for MDM-001 milestone 1. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35)
public class CharacterAssignmentStoreTest {
    private Context context;
    private CharacterAssignmentStore store;

    @Before public void setUp() {
        context = org.robolectric.RuntimeEnvironment.getApplication();
        context.deleteDatabase(CharacterAssignmentStore.DATABASE_NAME);
        store = new CharacterAssignmentStore(context);
    }

    @After public void tearDown() {
        if (store != null) store.close();
        context.deleteDatabase(CharacterAssignmentStore.DATABASE_NAME);
    }

    private void create() {
        store.create("a-001","HUGINN","ODIN","Monitor PR #51",
                "Observe only; no reruns or repository writes",
                "{\"rerun\":false,\"write_repository\":false}",
                "{\"poll_interval_minutes\":15,\"max_checks\":8}",
                "Workflow status, job steps, logs, final conclusion");
    }

    @Test public void createAndReopenPreservesAssignment() {
        create();
        store.close();
        store = new CharacterAssignmentStore(context);
        CharacterAssignmentStore.Assignment a=store.get("a-001");
        assertNotNull(a);
        assertEquals("HUGINN",a.character);
        assertEquals(CharacterAssignmentStore.Status.CREATED,a.status);
        assertNull(a.checkpointJson);
        assertNull(a.returnReportJson);
    }

    @Test public void lifecycleRejectsSkippingAuthorization() {
        create();
        try {
            store.transition("a-001",CharacterAssignmentStore.Status.QUEUED,"skip authorization");
            fail("Expected invalid transition");
        } catch(IllegalStateException expected) {
            assertTrue(expected.getMessage().contains("CREATED -> QUEUED"));
        }
    }

    @Test public void lifecycleAndPayloadsPersist() {
        create();
        store.transition("a-001",CharacterAssignmentStore.Status.AUTHORIZED,"Tyr authorization recorded");
        store.transition("a-001",CharacterAssignmentStore.Status.QUEUED,"Hodr scheduled");
        store.transition("a-001",CharacterAssignmentStore.Status.ACCEPTED,"Huginn accepted");
        store.transition("a-001",CharacterAssignmentStore.Status.RUNNING,"Monitoring started");
        store.checkpoint("a-001","{\"last_seen_run\":123}");
        store.returnReport("a-001","{\"outcome\":\"observed\",\"evidence\":[\"job steps\"]}");
        store.transition("a-001",CharacterAssignmentStore.Status.RETURNED,"Report returned");
        CharacterAssignmentStore.Assignment a=store.get("a-001");
        assertEquals(CharacterAssignmentStore.Status.RETURNED,a.status);
        assertTrue(a.checkpointJson.contains("123"));
        assertTrue(a.returnReportJson.contains("observed"));
        assertEquals(1,store.listOpen().size());
    }

    @Test public void rejectsUnknownCharacter() {
        try {
            store.create("a-002","NOBODY","ODIN","Objective","Scope","{}","{}","Evidence");
            fail("Expected invalid Character");
        } catch(IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("Unknown Nine Character"));
        }
    }

    @Test public void closedAssignmentIsNotListedAsOpen() {
        create();
        store.transition("a-001",CharacterAssignmentStore.Status.CLOSED,"Cancelled before authorization");
        assertTrue(store.listOpen().isEmpty());
    }
}
