package org.yggdrasil.scheduler.builder;

import android.app.Service;
import android.content.Intent;
import android.os.Handler;
import android.os.IBinder;
import android.os.Message;
import android.os.Messenger;
import android.os.RemoteException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** Debug-only remote-process probe used by the on-device lock instrumentation test. */
public final class BuilderRecoveryLockProbeService extends Service {
    static final int MSG_ACQUIRE = 1;
    static final int MSG_RELEASE = 2;
    static final int MSG_ACQUIRED = 3;
    static final int MSG_RELEASED = 4;
    static final int MSG_ERROR = 5;

    private Messenger messenger;
    private volatile CountDownLatch releaseLatch;

    @Override public void onCreate() {
        super.onCreate();
        messenger = new Messenger(new Handler(getMainLooper()) {
            @Override public void handleMessage(Message message) {
                if (message.what == MSG_ACQUIRE) {
                    acquireInWorker(message.replyTo);
                } else if (message.what == MSG_RELEASE) {
                    CountDownLatch latch = releaseLatch;
                    if (latch != null) latch.countDown();
                }
            }
        });
    }

    @Override public IBinder onBind(Intent intent) {
        return messenger.getBinder();
    }

    private void acquireInWorker(Messenger replyTo) {
        if (replyTo == null) return;
        CountDownLatch latch = new CountDownLatch(1);
        releaseLatch = latch;
        Thread worker = new Thread(() -> {
            try {
                BuilderRecoveryGenerationBarrier.withLock(getApplicationContext(), () -> {
                    send(replyTo, MSG_ACQUIRED, null);
                    if (!latch.await(10, TimeUnit.SECONDS))
                        throw new IllegalStateException("Timed out waiting for test release");
                    return null;
                });
                send(replyTo, MSG_RELEASED, null);
            } catch (Exception failure) {
                send(replyTo, MSG_ERROR, failure.getClass().getSimpleName()
                        + ": " + failure.getMessage());
            } finally {
                releaseLatch = null;
            }
        }, "recovery-lock-probe");
        worker.start();
    }

    private static void send(Messenger recipient, int what, String detail) {
        Message message = Message.obtain(null, what);
        if (detail != null) message.getData().putString("detail", detail);
        try { recipient.send(message); } catch (RemoteException ignored) { }
    }
}
