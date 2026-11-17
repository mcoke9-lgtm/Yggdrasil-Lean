package org.yggdrasil.scheduler.intake;

import static org.junit.Assert.*;

import android.content.Context;
import org.json.JSONObject;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35)
public final class IntakeStagingTest {
    private Context context;

    @Before public void setUp() {
        context = RuntimeEnvironment.getApplication();
        context.deleteDatabase("yim_intake_staging.db");
    }

    private String queue(String content) {
        return "{"
                + "\"format\":\"YIM-001-Work-Intake-Queue\","
                + "\"format_version\":2,"
                + "\"queue_id\":\"phone-intake\","
                + "\"packets\":[{"
                + "\"packet_id\":\"60d49085-b029-400a-adc2-f746b2bc2eb4\","
                + "\"captured_at\":\"2026-11-17T16:33:20.324Z\","
                + "\"source_kind\":\"chat_exchange\","
                + "\"source_reference\":null,"
                + "\"language\":\"English\","
                + "\"content\":" + content + ","
                + "\"metadata\":{},"
                + "\"placement_status\":\"awaiting_placement\","
                + "\"primary_logical_location\":null,"
                + "\"contextual_logical_locations\":[],"
                + "\"source_content_preserved_as_submitted\":true,"
                + "\"imported_record_id\":null,"
                + "\"imported_version_id\":null"
                + "}]"
                + "}";
    }

    @Test public void validatesAndStagesExactPhoneQueueWithoutRootsPlacement() throws Exception {
        String raw = queue("{\"text\":\"Living Tree phone intake test\",\"stage\":\"first capture\"}");
        IntakeQueueValidator.ValidatedQueue validated = IntakeQueueValidator.validate(raw);
        assertEquals("phone-intake", validated.queueId);
        assertEquals(1, validated.packetCount);
        assertEquals(1, validated.pendingCount);

        IntakeStagingStore store = new IntakeStagingStore(context);
        IntakeStagingStore.StageResult first = store.stage(validated);
        assertEquals(1, first.inserted);
        assertEquals(0, first.alreadyPresent);
        assertEquals(1, store.countPending());
        assertEquals(raw, store.listPending().get(0).sourceQueueJson);
        assertEquals("awaiting_placement", store.listPending().get(0).placementStatus);

        IntakeStagingStore.StageResult retry = store.stage(IntakeQueueValidator.validate(raw));
        assertEquals(0, retry.inserted);
        assertEquals(1, retry.alreadyPresent);
        assertEquals(1, store.countPending());
        store.close();
        assertEquals(1, new IntakeStagingStore(context).countPending());
    }

    @Test public void changedContentUnderSamePacketIdentityIsRejected() throws Exception {
        IntakeStagingStore store = new IntakeStagingStore(context);
        store.stage(IntakeQueueValidator.validate(queue("{\"text\":\"original\"}")));
        try {
            store.stage(IntakeQueueValidator.validate(queue("{\"text\":\"changed\"}")));
            fail("Changed content must not be accepted under an existing packet identity");
        } catch (IllegalStateException expected) {
            assertTrue(expected.getMessage().contains("different content"));
        }
        assertEquals(1, store.countPending());
    }

    @Test public void rejectsWrongVersionDuplicateIdsAndFalsePreservationMarker() throws Exception {
        String valid = queue("{}");
        try {
            IntakeQueueValidator.validate(valid.replace("\"format_version\":2", "\"format_version\":1"));
            fail("Version 1 must not be accepted by the Android v2 staging adapter");
        } catch (IllegalArgumentException expected) { }

        try {
            JSONObject root = new JSONObject(valid);
            org.json.JSONArray packets = root.getJSONArray("packets");
            packets.put(new JSONObject(packets.getJSONObject(0).toString()));
            IntakeQueueValidator.validate(root.toString());
            fail("Duplicate packet IDs must be rejected");
        } catch (IllegalArgumentException expected) { }

        String altered = valid.replace("\"source_content_preserved_as_submitted\":true",
                "\"source_content_preserved_as_submitted\":\"true\"");
        try {
            IntakeQueueValidator.validate(altered);
            fail("String truth values must not satisfy the boolean preservation marker");
        } catch (Exception expected) { }

        String numericIdentity = valid.replace("\"queue_id\":\"phone-intake\"", "\"queue_id\":123");
        try {
            IntakeQueueValidator.validate(numericIdentity);
            fail("Queue identity must be a string");
        } catch (Exception expected) { }
    }
}
