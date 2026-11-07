package org.yggdrasil.scheduler.builder;

import android.app.Service;
import android.app.ActivityManager;
import android.os.Build;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Message;
import android.os.Messenger;
import android.os.Process;
import android.os.RemoteException;

import java.util.List;

/**
 * One Builder's private Android process and append-only local journal.
 * Concrete subclasses are declared with distinct android:process values.
 */
public abstract class BuilderRoleProcessService extends Service {
    public static final int MESSAGE_HEALTH = 1;
    public static final int MESSAGE_APPEND = 2;
    public static final int MESSAGE_READ = 3;
    public static final int RESPONSE = 101;
    public static final int ERROR = 199;
    public static final String KEY_ROLE = "role";
    public static final String KEY_PID = "pid";
    public static final String KEY_PROCESS = "process_name";
    public static final String KEY_THREAD = "thread_id";
    public static final String KEY_TURN_ID = "turn_id";
    public static final String KEY_BODY = "body";
    public static final String KEY_EVIDENCE = "evidence_status";
    public static final String KEY_RESULT = "result_json";

    private HandlerThread thread;
    private Messenger messenger;
    private BuilderConversationStore store;

    protected abstract BuilderRole role();

    @Override public void onCreate() {
        super.onCreate();
        store = new BuilderConversationStore(getApplicationContext(),
                "builder-role-" + role().name().toLowerCase(java.util.Locale.ROOT) + ".db");
        thread = new HandlerThread("builder-role-" + role().name().toLowerCase(java.util.Locale.ROOT));
        thread.start();
        messenger = new Messenger(new Handler(thread.getLooper()) {
            @Override public void handleMessage(Message request) {
                if (request.replyTo == null) return;
                try {
                    Bundle input = request.getData();
                    Bundle output = new Bundle();
                    if (request.what == MESSAGE_HEALTH) {
                        output.putString(KEY_ROLE, role().name());
                        output.putInt(KEY_PID, Process.myPid());
                        output.putString(KEY_PROCESS, currentProcessName());
                    } else if (request.what == MESSAGE_APPEND) {
                        String speaker = input.getString(KEY_ROLE);
                        if (!role().name().equals(speaker)) {
                            throw new SecurityException("A Builder process may append only its own role identity.");
                        }
                        String threadId = input.getString(KEY_THREAD);
                        String turnId = input.getString(KEY_TURN_ID);
                        String body = input.getString(KEY_BODY);
                        String evidence = input.getString(KEY_EVIDENCE);
                        BuilderConversationProtocol.Turn turn = new BuilderConversationProtocol.Turn(
                                BuilderConversationProtocol.CURRENT_VERSION,
                                threadId, turnId,
                                BuilderConversationProtocol.Participant.valueOf(role().name()),
                                null, null,
                                BuilderConversationProtocol.TurnType.CONTRIBUTION,
                                BuilderConversationProtocol.EvidenceStatus.valueOf(evidence),
                                System.currentTimeMillis(), body);
                        store.append(turn);
                        output.putBoolean("appended", true);
                    } else if (request.what == MESSAGE_READ) {
                        List<BuilderConversationProtocol.Turn> turns =
                                store.readThread(input.getString(KEY_THREAD));
                        org.json.JSONArray rows = new org.json.JSONArray();
                        for (BuilderConversationProtocol.Turn turn : turns) {
                            org.json.JSONObject row = new org.json.JSONObject();
                            row.put(KEY_TURN_ID, turn.turnId);
                            row.put(KEY_ROLE, turn.speaker.name());
                            row.put(KEY_BODY, turn.body);
                            row.put(KEY_EVIDENCE, turn.evidenceStatus.name());
                            rows.put(row);
                        }
                        output.putString(KEY_RESULT, rows.toString());
                    } else {
                        throw new IllegalArgumentException("Unsupported role-process message.");
                    }
                    Message response = Message.obtain(null, RESPONSE);
                    response.setData(output);
                    request.replyTo.send(response);
                } catch (Exception failure) {
                    Bundle output = new Bundle();
                    output.putString("error", failure.getMessage() == null
                            ? failure.getClass().getSimpleName() : failure.getMessage());
                    Message response = Message.obtain(null, ERROR);
                    response.setData(output);
                    try { request.replyTo.send(response); } catch (RemoteException ignored) { }
                }
            }
        });
    }

    private String currentProcessName() {
        if (Build.VERSION.SDK_INT >= 28) return android.app.Application.getProcessName();
        ActivityManager manager = (ActivityManager) getSystemService(ACTIVITY_SERVICE);
        if (manager != null) {
            for (ActivityManager.RunningAppProcessInfo process : manager.getRunningAppProcesses()) {
                if (process.pid == Process.myPid()) return process.processName;
            }
        }
        return getPackageName() + ":builder_" + role().name().toLowerCase(java.util.Locale.ROOT);
    }

    @Override public IBinder onBind(Intent intent) { return messenger.getBinder(); }

    @Override public void onDestroy() {
        if (store != null) store.close();
        if (thread != null) thread.quitSafely();
        super.onDestroy();
    }
}
