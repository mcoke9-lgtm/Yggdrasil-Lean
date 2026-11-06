package org.yggdrasil.scheduler.builder;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.HandlerThread;
import android.os.Message;
import android.os.Messenger;
import java.util.ArrayList;
import org.junit.Test;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.android.controller.ServiceController;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.RobolectricTestRunner;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35)
public class BuilderRuntimeServiceTest {
    @Test public void healthRequestReturnsPersistentIdentityAndNineRoles() throws Exception {
        ServiceController<BuilderRuntimeService> controller =
                Robolectric.buildService(BuilderRuntimeService.class).create();
        BuilderRuntimeService service = controller.get();
        IBinder binder = service.onBind(new Intent(RuntimeEnvironment.getApplication(),
                BuilderRuntimeService.class));
        Messenger host = new Messenger(binder);

        final Bundle[] received = new Bundle[1];
        final int[] healthResponseType = new int[1];
        CountDownLatch responseReceived = new CountDownLatch(1);
        HandlerThread replyThread = new HandlerThread("runtime-service-test-reply");
        replyThread.start();
        Messenger client = new Messenger(new Handler(replyThread.getLooper()) {
            @Override public void handleMessage(Message message) {
                received[0] = message.getData();
                healthResponseType[0] = message.what;
                responseReceived.countDown();
            }
        });
        Message request = Message.obtain(null, BuilderRuntimeProtocol.MESSAGE_GET_HEALTH);
        request.replyTo = client;
        host.send(request);
        assertTrue("Runtime service did not reply",
                responseReceived.await(3, TimeUnit.SECONDS));

        assertNotNull(received[0]);
        assertEquals(BuilderRuntimeProtocol.MESSAGE_HEALTH, healthResponseType[0]);
        assertEquals(BuilderRuntimeProtocol.VERSION,
                received[0].getInt(BuilderRuntimeProtocol.KEY_PROTOCOL_VERSION));
        assertEquals("READY", received[0].getString(BuilderRuntimeProtocol.KEY_STATUS));
        assertNotNull(received[0].getString(BuilderRuntimeProtocol.KEY_RUNTIME_ID));
        ArrayList<String> roles = received[0].getStringArrayList(
                BuilderRuntimeProtocol.KEY_BUILDERS);
        assertNotNull(roles);
        assertEquals(9, roles.size());
        assertTrue(roles.contains("MARI"));
        ArrayList<String> capabilities = received[0].getStringArrayList(
                BuilderRuntimeProtocol.KEY_CAPABILITIES);
        assertNotNull(capabilities);
        assertTrue(capabilities.contains("PERSISTENT_RUNTIME_IDENTITY"));
        assertTrue(capabilities.contains("LOCAL_ANDROID_IPC"));
        assertTrue(capabilities.contains("DEVICE_CAPABILITY_INVENTORY"));
        org.json.JSONObject device = new org.json.JSONObject(
                received[0].getString(BuilderRuntimeProtocol.KEY_DEVICE_INVENTORY_JSON));
        assertEquals(1, device.getInt("schema_version"));
        assertTrue(device.getInt("api_level") > 0);
        assertTrue(device.has("memory_total_bytes"));
        assertTrue(device.has("data_storage_total_bytes"));
        assertEquals("DEVICE_CAPABILITIES_ONLY_NO_PERSONAL_IDENTIFIERS",
                device.getString("collection_scope"));
        assertTrue(!device.has("android_id"));
        assertTrue(!device.has("imei"));

        controller.destroy();
        replyThread.quitSafely();
    }
    @Test public void runtimeOwnsAppendOnlyConversationJournal() throws Exception {
        RuntimeEnvironment.getApplication().deleteDatabase("builder-conversations.db");
        ServiceController<BuilderRuntimeService> controller =
                Robolectric.buildService(BuilderRuntimeService.class).create();
        Messenger host = new Messenger(controller.get().onBind(
                new Intent(RuntimeEnvironment.getApplication(), BuilderRuntimeService.class)));

        Bundle turn = new Bundle();
        turn.putInt(BuilderRuntimeProtocol.KEY_TURN_PROTOCOL,
                BuilderConversationProtocol.CURRENT_VERSION);
        turn.putString(BuilderRuntimeProtocol.KEY_TURN_THREAD, "runtime-thread");
        turn.putString(BuilderRuntimeProtocol.KEY_TURN_ID, "runtime-turn-1");
        turn.putString(BuilderRuntimeProtocol.KEY_TURN_SPEAKER, "USER");
        turn.putString(BuilderRuntimeProtocol.KEY_TURN_RECIPIENT, null);
        turn.putString(BuilderRuntimeProtocol.KEY_TURN_REPLY_TO, null);
        turn.putString(BuilderRuntimeProtocol.KEY_TURN_TYPE, "CONTRIBUTION");
        turn.putString(BuilderRuntimeProtocol.KEY_TURN_EVIDENCE, "UNKNOWN");
        turn.putLong(BuilderRuntimeProtocol.KEY_TURN_TIMESTAMP, 100L);
        turn.putString(BuilderRuntimeProtocol.KEY_TURN_BODY, "Persisted by the native host");

        Bundle appended = send(host, BuilderRuntimeProtocol.MESSAGE_APPEND_TURN, turn);
        assertTrue(appended.getBoolean("appended"));

        Bundle query = new Bundle();
        query.putString(BuilderRuntimeProtocol.KEY_THREAD_ID, "runtime-thread");
        Bundle read = send(host, BuilderRuntimeProtocol.MESSAGE_READ_THREAD, query);
        org.json.JSONArray rows = new org.json.JSONArray(
                read.getString(BuilderRuntimeProtocol.KEY_RESULT_JSON));
        assertEquals(1, rows.length());
        assertEquals("Persisted by the native host", rows.getJSONObject(0).getString("body"));

        Bundle listed = send(host, BuilderRuntimeProtocol.MESSAGE_LIST_THREADS, new Bundle());
        org.json.JSONArray ids = new org.json.JSONArray(
                listed.getString(BuilderRuntimeProtocol.KEY_RESULT_JSON));
        assertEquals("runtime-thread", ids.getString(0));

        Bundle exported = send(host, BuilderRuntimeProtocol.MESSAGE_EXPORT_SNAPSHOT, new Bundle());
        org.json.JSONObject snapshot = new org.json.JSONObject(
                exported.getString(BuilderRuntimeProtocol.KEY_RESULT_JSON));
        assertEquals(1, snapshot.getInt("turn_count"));
        assertNotNull(snapshot.getString("content_sha256"));

        controller.destroy();
        RuntimeEnvironment.getApplication().deleteDatabase("builder-conversations.db");
    }

    private static Bundle send(Messenger host, int type, Bundle data) throws Exception {
        final Bundle[] received = new Bundle[1];
        final int[] responseType = new int[1];
        CountDownLatch responseReceived = new CountDownLatch(1);
        HandlerThread replyThread = new HandlerThread("runtime-journal-test-reply");
        replyThread.start();
        try {
            Messenger client = new Messenger(new Handler(replyThread.getLooper()) {
                @Override public void handleMessage(Message message) {
                    received[0] = message.getData();
                    responseType[0] = message.what;
                    responseReceived.countDown();
                }
            });
            Message request = Message.obtain(null, type);
            request.setData(data);
            request.replyTo = client;
            host.send(request);
            assertTrue("Runtime journal did not reply",
                    responseReceived.await(3, TimeUnit.SECONDS));
            assertEquals(BuilderRuntimeProtocol.MESSAGE_RESULT, responseType[0]);
            return received[0];
        } finally {
            replyThread.quitSafely();
        }
    }

}
