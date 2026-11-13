package org.yggdrasil.scheduler.builder;

import static org.junit.Assert.*;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Message;
import android.os.Messenger;
import android.os.RemoteException;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** Runs on an Android device/emulator; JVM/Robolectric cannot prove OS process separation. */
@RunWith(AndroidJUnit4.class)
public class NineBuilderProcessIsolationTest {
    private static final String THREAD = "nine-builder-process-isolation-001";

    private static final Class<?>[] SERVICES = {
            HuginnProcessService.class, MuninnProcessService.class,
            KvasirProcessService.class, MariProcessService.class,
            HodrProcessService.class, TyrProcessService.class,
            LokiProcessService.class, OdinProcessService.class,
            BaldrProcessService.class
    };
    private static final String[] ROLES = {
            "HUGINN", "MUNINN", "KVASIR", "MARI", "HODR", "TYR", "LOKI", "ODIN", "BALDR"
    };

    @Test public void allNineRolesRunInDistinctProcessesAndOwnTheirJournals() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        List<ServiceConnection> connections = new ArrayList<>();
        List<Messenger> hosts = new ArrayList<>();
        HandlerThread replyThread = new HandlerThread("nine-process-test-replies");
        replyThread.start();
        try {
            for (Class<?> serviceClass : SERVICES) {
                CountDownLatch connected = new CountDownLatch(1);
                Messenger[] host = new Messenger[1];
                ServiceConnection connection = new ServiceConnection() {
                    @Override public void onServiceConnected(ComponentName name, IBinder binder) {
                        host[0] = new Messenger(binder);
                        connected.countDown();
                    }
                    @Override public void onServiceDisconnected(ComponentName name) { }
                };
                connections.add(connection);
                Intent intent = new Intent(context, serviceClass);
                assertTrue("Could not bind " + serviceClass.getSimpleName(),
                        context.bindService(intent, connection, Context.BIND_AUTO_CREATE));
                assertTrue("Timed out binding " + serviceClass.getSimpleName(),
                        connected.await(10, TimeUnit.SECONDS));
                hosts.add(host[0]);
            }

            Set<Integer> pids = new HashSet<>();
            Set<String> processNames = new HashSet<>();
            for (int i = 0; i < hosts.size(); i++) {
                Bundle health = request(hosts.get(i), BuilderRoleProcessService.MESSAGE_HEALTH,
                        new Bundle());
                assertEquals(ROLES[i], health.getString(BuilderRoleProcessService.KEY_ROLE));
                pids.add(health.getInt(BuilderRoleProcessService.KEY_PID));
                processNames.add(health.getString(BuilderRoleProcessService.KEY_PROCESS));
            }
            assertEquals("Every Builder must occupy a distinct OS process", 9, pids.size());
            assertEquals("Every Builder must have a distinct Android process name", 9, processNames.size());

            // Each process accepts only its own role identity and persists to its own DB.
            for (int i = 0; i < hosts.size(); i++) {
                Bundle append = new Bundle();
                append.putString(BuilderRoleProcessService.KEY_ROLE, ROLES[i]);
                append.putString(BuilderRoleProcessService.KEY_THREAD, THREAD);
                append.putString(BuilderRoleProcessService.KEY_TURN_ID, "process-turn-" + ROLES[i]);
                append.putString(BuilderRoleProcessService.KEY_BODY, "private checkpoint for " + ROLES[i]);
                append.putString(BuilderRoleProcessService.KEY_EVIDENCE, "OBSERVED");
                Bundle result = request(hosts.get(i),
                        BuilderRoleProcessService.MESSAGE_APPEND, append);
                assertTrue(result.getBoolean("appended"));
            }
            for (int i = 0; i < hosts.size(); i++) {
                Bundle query = new Bundle();
                query.putString(BuilderRoleProcessService.KEY_THREAD, THREAD);
                Bundle result = request(hosts.get(i),
                        BuilderRoleProcessService.MESSAGE_READ, query);
                org.json.JSONArray rows = new org.json.JSONArray(
                        result.getString(BuilderRoleProcessService.KEY_RESULT));
                assertEquals(1, rows.length());
                assertEquals("process-turn-" + ROLES[i],
                        rows.getJSONObject(0).getString(BuilderRoleProcessService.KEY_TURN_ID));
                assertEquals(ROLES[i], rows.getJSONObject(0).getString(BuilderRoleProcessService.KEY_ROLE));
            }
        } finally {
            for (ServiceConnection connection : connections) {
                try { context.unbindService(connection); } catch (IllegalArgumentException ignored) { }
            }
            replyThread.quitSafely();
        }
    }

    private static Bundle request(Messenger host, int type, Bundle data)
            throws Exception {
        final Bundle[] response = new Bundle[1];
        final int[] responseType = new int[1];
        CountDownLatch latch = new CountDownLatch(1);
        HandlerThread waiter = new HandlerThread("nine-process-response-" + type
                + "-" + System.nanoTime());
        waiter.start();
        Messenger reply = new Messenger(new Handler(waiter.getLooper()) {
            @Override public void handleMessage(Message message) {
                response[0] = message.getData();
                responseType[0] = message.what;
                latch.countDown();
            }
        });
        try {
            Message request = Message.obtain(null, type);
            request.setData(data);
            request.replyTo = reply;
            host.send(request);
            assertTrue("Timed out waiting for role process", latch.await(10, TimeUnit.SECONDS));
            assertEquals("Role process returned an error: "
                    + (response[0] == null ? "no response" : response[0].getString("error")),
                    BuilderRoleProcessService.RESPONSE, responseType[0]);
            return response[0];
        } finally {
            waiter.quitSafely();
        }
    }
}
