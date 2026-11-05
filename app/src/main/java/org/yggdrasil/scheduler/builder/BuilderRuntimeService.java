package org.yggdrasil.scheduler.builder;

import android.app.Service;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.Messenger;
import android.os.RemoteException;
import java.util.ArrayList;

/**
 * First Builder-native host process.
 *
 * This first milestone owns a stable local runtime identity and exposes a small,
 * versioned health protocol over Android IPC. It does not call a model, access the
 * network, or claim that the nine roles are independent AI workers.
 */
public final class BuilderRuntimeService extends Service {
    private BuilderRuntimeStore store;
    private BuilderRuntimeStore.NodeIdentity identity;
    private long processStartedAt;
    private Messenger messenger;

    @Override public void onCreate() {
        super.onCreate();
        processStartedAt = System.currentTimeMillis();
        store = new BuilderRuntimeStore(getApplicationContext());
        identity = store.getOrCreateIdentity();
        messenger = new Messenger(new IncomingHandler());
    }

    @Override public IBinder onBind(Intent intent) {
        return messenger.getBinder();
    }

    @Override public void onDestroy() {
        if (store != null) store.close();
        super.onDestroy();
    }

    private final class IncomingHandler extends Handler {
        IncomingHandler() { super(Looper.getMainLooper()); }

        @Override public void handleMessage(Message request) {
            if (request.what != BuilderRuntimeProtocol.MESSAGE_GET_HEALTH) {
                sendError(request, "Unsupported runtime message type");
                return;
            }
            if (request.replyTo == null) return;

            Message response = Message.obtain(null, BuilderRuntimeProtocol.MESSAGE_HEALTH);
            Bundle data = new Bundle();
            data.putInt(BuilderRuntimeProtocol.KEY_PROTOCOL_VERSION,
                    BuilderRuntimeProtocol.VERSION);
            data.putString(BuilderRuntimeProtocol.KEY_RUNTIME_ID, identity.runtimeId);
            data.putLong(BuilderRuntimeProtocol.KEY_CREATED_AT,
                    identity.createdAtEpochMillis);
            data.putLong(BuilderRuntimeProtocol.KEY_STARTED_AT, processStartedAt);
            data.putString(BuilderRuntimeProtocol.KEY_PROCESS_NAME,
                    BuilderRuntimeProtocol.PROCESS_NAME);
            data.putString(BuilderRuntimeProtocol.KEY_STATUS, "READY");
            ArrayList<String> builders = new ArrayList<>();
            for (BuilderRole role : BuilderRole.values())
                builders.add(role.name());
            data.putStringArrayList(BuilderRuntimeProtocol.KEY_BUILDERS, builders);
            ArrayList<String> capabilities = new ArrayList<>();
            capabilities.add("PERSISTENT_RUNTIME_IDENTITY");
            capabilities.add("HEALTH_STATUS");
            capabilities.add("LOCAL_ANDROID_IPC");
            data.putStringArrayList(BuilderRuntimeProtocol.KEY_CAPABILITIES, capabilities);
            response.setData(data);
            try {
                request.replyTo.send(response);
            } catch (RemoteException ignored) {
                // Client disconnection is normal; durable identity remains in SQLite.
            }
        }

        private void sendError(Message request, String reason) {
            if (request.replyTo == null) return;
            Message response = Message.obtain(null, BuilderRuntimeProtocol.MESSAGE_ERROR);
            Bundle data = new Bundle();
            data.putInt(BuilderRuntimeProtocol.KEY_PROTOCOL_VERSION,
                    BuilderRuntimeProtocol.VERSION);
            data.putString(BuilderRuntimeProtocol.KEY_ERROR, reason);
            response.setData(data);
            try {
                request.replyTo.send(response);
            } catch (RemoteException ignored) {
                // No state change is needed when a client has disconnected.
            }
        }
    }
}
