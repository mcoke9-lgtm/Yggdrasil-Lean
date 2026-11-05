package org.yggdrasil.scheduler.builder;

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
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Client for the Builder-native host's canonical conversation journal.
 * Calls are synchronous and must be made off the Android main thread.
 */
public final class BuilderRuntimeJournalClient implements BuilderConversationJournal {
    private static final long CONNECT_TIMEOUT_SECONDS = 8;
    private static final long RESPONSE_TIMEOUT_SECONDS = 20;
    private final Context context;

    public BuilderRuntimeJournalClient(Context context) {
        this.context = context.getApplicationContext();
    }

    @Override public void append(BuilderConversationProtocol.Turn turn) {
        Bundle data = new Bundle();
        data.putInt(BuilderRuntimeProtocol.KEY_TURN_PROTOCOL, turn.protocolVersion);
        data.putString(BuilderRuntimeProtocol.KEY_TURN_THREAD, turn.threadId);
        data.putString(BuilderRuntimeProtocol.KEY_TURN_ID, turn.turnId);
        data.putString(BuilderRuntimeProtocol.KEY_TURN_SPEAKER, turn.speaker.name());
        data.putString(BuilderRuntimeProtocol.KEY_TURN_RECIPIENT,
                turn.recipient == null ? null : turn.recipient.name());
        data.putString(BuilderRuntimeProtocol.KEY_TURN_REPLY_TO, turn.replyToTurnId);
        data.putString(BuilderRuntimeProtocol.KEY_TURN_TYPE, turn.type.name());
        data.putString(BuilderRuntimeProtocol.KEY_TURN_EVIDENCE, turn.evidenceStatus.name());
        data.putLong(BuilderRuntimeProtocol.KEY_TURN_TIMESTAMP, turn.timestampEpochMillis);
        data.putString(BuilderRuntimeProtocol.KEY_TURN_BODY, turn.body);
        request(BuilderRuntimeProtocol.MESSAGE_APPEND_TURN, data);
    }

    @Override public List<BuilderConversationProtocol.Turn> readThread(String threadId) {
        Bundle data = new Bundle();
        data.putString(BuilderRuntimeProtocol.KEY_THREAD_ID, threadId);
        String json = request(BuilderRuntimeProtocol.MESSAGE_READ_THREAD, data)
                .getString(BuilderRuntimeProtocol.KEY_RESULT_JSON, "[]");
        try {
            org.json.JSONArray rows = new org.json.JSONArray(json);
            List<BuilderConversationProtocol.Turn> turns = new ArrayList<>();
            for (int i = 0; i < rows.length(); i++) {
                org.json.JSONObject row = rows.getJSONObject(i);
                String recipient = row.isNull("recipient") ? null : row.getString("recipient");
                String replyTo = row.isNull("reply_to_turn_id") ? null : row.getString("reply_to_turn_id");
                turns.add(new BuilderConversationProtocol.Turn(
                        row.getInt("protocol_version"), row.getString("thread_id"),
                        row.getString("turn_id"),
                        BuilderConversationProtocol.Participant.valueOf(row.getString("speaker")),
                        recipient == null ? null : BuilderConversationProtocol.Participant.valueOf(recipient),
                        replyTo,
                        BuilderConversationProtocol.TurnType.valueOf(row.getString("turn_type")),
                        BuilderConversationProtocol.EvidenceStatus.valueOf(row.getString("evidence_status")),
                        row.getLong("timestamp_epoch_millis"), row.getString("body")));
            }
            return Collections.unmodifiableList(turns);
        } catch (org.json.JSONException e) {
            throw new IllegalStateException("Builder runtime returned an invalid thread snapshot", e);
        }
    }

    @Override public List<String> listThreadIds() {
        String json = request(BuilderRuntimeProtocol.MESSAGE_LIST_THREADS, new Bundle())
                .getString(BuilderRuntimeProtocol.KEY_RESULT_JSON, "[]");
        try {
            org.json.JSONArray rows = new org.json.JSONArray(json);
            List<String> ids = new ArrayList<>();
            for (int i = 0; i < rows.length(); i++) ids.add(rows.getString(i));
            return Collections.unmodifiableList(ids);
        } catch (org.json.JSONException e) {
            throw new IllegalStateException("Builder runtime returned an invalid thread list", e);
        }
    }

    @Override public org.json.JSONObject exportSnapshot() {
        String json = request(BuilderRuntimeProtocol.MESSAGE_EXPORT_SNAPSHOT, new Bundle())
                .getString(BuilderRuntimeProtocol.KEY_RESULT_JSON, "{}");
        try {
            return new org.json.JSONObject(json);
        } catch (org.json.JSONException e) {
            throw new IllegalStateException("Builder runtime returned an invalid backup snapshot", e);
        }
    }

    @Override public void close() {
        // Each operation owns and releases its short-lived service connection.
    }

    private Bundle request(int operation, Bundle payload) {
        HandlerThread replyThread = new HandlerThread("builder-runtime-client-reply");
        replyThread.start();
        CountDownLatch connected = new CountDownLatch(1);
        CountDownLatch completed = new CountDownLatch(1);
        AtomicReference<Messenger> host = new AtomicReference<>();
        AtomicReference<Bundle> result = new AtomicReference<>();
        AtomicReference<RuntimeException> failure = new AtomicReference<>();
        Messenger reply = new Messenger(new Handler(replyThread.getLooper()) {
            @Override public void handleMessage(Message response) {
                Bundle responseData = response.getData();
                if (response.what == BuilderRuntimeProtocol.MESSAGE_ERROR) {
                    failure.set(new IllegalStateException(responseData.getString(
                            BuilderRuntimeProtocol.KEY_ERROR, "Builder runtime rejected request")));
                } else if (response.what == BuilderRuntimeProtocol.MESSAGE_RESULT) {
                    result.set(responseData);
                } else {
                    failure.set(new IllegalStateException("Unexpected Builder runtime response"));
                }
                completed.countDown();
            }
        });
        ServiceConnection connection = new ServiceConnection() {
            @Override public void onServiceConnected(ComponentName name, IBinder binder) {
                host.set(new Messenger(binder));
                connected.countDown();
            }
            @Override public void onServiceDisconnected(ComponentName name) {
                failure.set(new IllegalStateException("Builder-native host disconnected"));
                connected.countDown();
                completed.countDown();
            }
        };
        boolean bound = false;
        try {
            bound = context.bindService(new Intent(context, BuilderRuntimeService.class),
                    connection, Context.BIND_AUTO_CREATE);
            if (!bound) throw new IllegalStateException("Could not bind to Builder-native host");
            if (!connected.await(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS))
                throw new IllegalStateException("Timed out connecting to Builder-native host");
            if (failure.get() != null) throw failure.get();
            Message message = Message.obtain(null, operation);
            message.setData(payload);
            message.replyTo = reply;
            host.get().send(message);
            if (!completed.await(RESPONSE_TIMEOUT_SECONDS, TimeUnit.SECONDS))
                throw new IllegalStateException("Timed out waiting for Builder-native journal");
            if (failure.get() != null) throw failure.get();
            Bundle response = result.get();
            if (response == null) throw new IllegalStateException("Builder runtime returned no result");
            return response;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while communicating with Builder runtime", e);
        } catch (RemoteException e) {
            throw new IllegalStateException("Builder runtime IPC failed", e);
        } finally {
            if (bound) {
                try { context.unbindService(connection); } catch (IllegalArgumentException ignored) {}
            }
            replyThread.quitSafely();
        }
    }
}
