package org.yggdrasil.scheduler.builder;

import android.app.Service;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Message;
import android.os.Messenger;
import android.os.RemoteException;
import java.util.ArrayList;
import java.util.List;

/**
 * Builder-native Android host process.
 *
 * This process owns the canonical conversation journal and persistent runtime identity.
 * It performs no model inference and exposes no public network listener.
 */
public final class BuilderRuntimeService extends Service {
    private BuilderRuntimeStore identityStore;
    private BuilderRuntimeStore.NodeIdentity identity;
    private BuilderConversationJournal journal;
    private long processStartedAt;
    private HandlerThread workerThread;
    private Messenger messenger;

    @Override public void onCreate() {
        super.onCreate();
        processStartedAt = System.currentTimeMillis();
        identityStore = new BuilderRuntimeStore(getApplicationContext());
        identity = identityStore.getOrCreateIdentity();
        journal = new BuilderRecoveryConversationJournal(getApplicationContext());
        workerThread = new HandlerThread("builder-native-runtime-worker");
        workerThread.start();
        messenger = new Messenger(new RuntimeHandler(workerThread.getLooper()));
    }

    @Override public IBinder onBind(Intent intent) {
        return messenger.getBinder();
    }

    @Override public void onDestroy() {
        if (journal != null) journal.close();
        if (identityStore != null) identityStore.close();
        if (workerThread != null) workerThread.quitSafely();
        super.onDestroy();
    }

    private final class RuntimeHandler extends Handler {
        RuntimeHandler(android.os.Looper looper) { super(looper); }

        @Override public void handleMessage(Message request) {
            if (request.replyTo == null) return;
            try {
                Bundle input = request.getData();
                Bundle output = new Bundle();
                if (request.what == BuilderRuntimeProtocol.MESSAGE_GET_HEALTH) {
                    output.putInt(BuilderRuntimeProtocol.KEY_PROTOCOL_VERSION,
                            BuilderRuntimeProtocol.VERSION);
                    output.putString(BuilderRuntimeProtocol.KEY_RUNTIME_ID, identity.runtimeId);
                    output.putLong(BuilderRuntimeProtocol.KEY_CREATED_AT,
                            identity.createdAtEpochMillis);
                    output.putLong(BuilderRuntimeProtocol.KEY_STARTED_AT, processStartedAt);
                    output.putString(BuilderRuntimeProtocol.KEY_PROCESS_NAME,
                            BuilderRuntimeProtocol.PROCESS_NAME);
                    output.putString(BuilderRuntimeProtocol.KEY_STATUS, "READY");
                    ArrayList<String> builders = new ArrayList<>();
                    for (BuilderRole role : BuilderRole.values()) builders.add(role.name());
                    output.putStringArrayList(BuilderRuntimeProtocol.KEY_BUILDERS, builders);
                    ArrayList<String> capabilities = new ArrayList<>();
                    capabilities.add("PERSISTENT_RUNTIME_IDENTITY");
                    capabilities.add("CANONICAL_CONVERSATION_JOURNAL");
                    capabilities.add("APPEND_ONLY_TURN_STORAGE");
                    capabilities.add("THREAD_RECOVERY_AND_EXPORT");
                    capabilities.add("LOCAL_ANDROID_IPC");
                    capabilities.add("DEVICE_CAPABILITY_INVENTORY");
                    output.putString(BuilderRuntimeProtocol.KEY_DEVICE_INVENTORY_JSON,
                            new AndroidDeviceInventory(getApplicationContext()).collect().toString());
                    output.putStringArrayList(BuilderRuntimeProtocol.KEY_CAPABILITIES, capabilities);
                } else if (request.what == BuilderRuntimeProtocol.MESSAGE_GET_DEVICE_INVENTORY) {
                    output.putString(BuilderRuntimeProtocol.KEY_DEVICE_INVENTORY_JSON,
                            new AndroidDeviceInventory(getApplicationContext()).collect().toString());
                } else if (request.what == BuilderRuntimeProtocol.MESSAGE_APPEND_TURN) {
                    BuilderConversationProtocol.Turn turn = readTurn(input);
                    journal.append(turn);
                    output.putBoolean("appended", true);
                } else if (request.what == BuilderRuntimeProtocol.MESSAGE_READ_THREAD) {
                    output.putString(BuilderRuntimeProtocol.KEY_RESULT_JSON,
                            encodeTurns(journal.readThread(input.getString(
                                    BuilderRuntimeProtocol.KEY_THREAD_ID))));
                } else if (request.what == BuilderRuntimeProtocol.MESSAGE_LIST_THREADS) {
                    org.json.JSONArray ids = new org.json.JSONArray();
                    for (String id : journal.listThreadIds()) ids.put(id);
                    output.putString(BuilderRuntimeProtocol.KEY_RESULT_JSON, ids.toString());
                } else if (request.what == BuilderRuntimeProtocol.MESSAGE_EXPORT_SNAPSHOT) {
                    output.putString(BuilderRuntimeProtocol.KEY_RESULT_JSON,
                            journal.exportSnapshot().toString());
                } else {
                    throw new IllegalArgumentException("Unsupported runtime message type");
                }
                int responseType = request.what == BuilderRuntimeProtocol.MESSAGE_GET_HEALTH
                        ? BuilderRuntimeProtocol.MESSAGE_HEALTH
                        : BuilderRuntimeProtocol.MESSAGE_RESULT;
                send(request.replyTo, responseType, output);
            } catch (Exception failure) {
                String reason = failure.getMessage() == null
                        ? failure.getClass().getSimpleName() : failure.getMessage();
                Bundle error = new Bundle();
                error.putInt(BuilderRuntimeProtocol.KEY_PROTOCOL_VERSION,
                        BuilderRuntimeProtocol.VERSION);
                error.putString(BuilderRuntimeProtocol.KEY_ERROR, reason);
                send(request.replyTo, BuilderRuntimeProtocol.MESSAGE_ERROR, error);
            }
        }
    }

    private static BuilderConversationProtocol.Turn readTurn(Bundle data) {
        String recipient = data.getString(BuilderRuntimeProtocol.KEY_TURN_RECIPIENT);
        return new BuilderConversationProtocol.Turn(
                data.getInt(BuilderRuntimeProtocol.KEY_TURN_PROTOCOL),
                data.getString(BuilderRuntimeProtocol.KEY_TURN_THREAD),
                data.getString(BuilderRuntimeProtocol.KEY_TURN_ID),
                BuilderConversationProtocol.Participant.valueOf(
                        data.getString(BuilderRuntimeProtocol.KEY_TURN_SPEAKER)),
                recipient == null ? null : BuilderConversationProtocol.Participant.valueOf(recipient),
                data.getString(BuilderRuntimeProtocol.KEY_TURN_REPLY_TO),
                BuilderConversationProtocol.TurnType.valueOf(
                        data.getString(BuilderRuntimeProtocol.KEY_TURN_TYPE)),
                BuilderConversationProtocol.EvidenceStatus.valueOf(
                        data.getString(BuilderRuntimeProtocol.KEY_TURN_EVIDENCE)),
                data.getLong(BuilderRuntimeProtocol.KEY_TURN_TIMESTAMP),
                data.getString(BuilderRuntimeProtocol.KEY_TURN_BODY));
    }

    private static String encodeTurns(List<BuilderConversationProtocol.Turn> turns)
            throws org.json.JSONException {
        org.json.JSONArray rows = new org.json.JSONArray();
        for (BuilderConversationProtocol.Turn turn : turns) {
            org.json.JSONObject row = new org.json.JSONObject();
            row.put("protocol_version", turn.protocolVersion);
            row.put("thread_id", turn.threadId);
            row.put("turn_id", turn.turnId);
            row.put("speaker", turn.speaker.name());
            row.put("recipient", turn.recipient == null
                    ? org.json.JSONObject.NULL : turn.recipient.name());
            row.put("reply_to_turn_id", turn.replyToTurnId == null
                    ? org.json.JSONObject.NULL : turn.replyToTurnId);
            row.put("turn_type", turn.type.name());
            row.put("evidence_status", turn.evidenceStatus.name());
            row.put("timestamp_epoch_millis", turn.timestampEpochMillis);
            row.put("body", turn.body);
            rows.put(row);
        }
        return rows.toString();
    }

    private static void send(Messenger recipient, int type, Bundle data) {
        Message response = Message.obtain(null, type);
        response.setData(data);
        try {
            recipient.send(response);
        } catch (RemoteException ignored) {
            // A disconnected client cannot undo a committed journal append.
        }
    }
}
