package org.yggdrasil.scheduler.builder;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Message;
import android.os.Messenger;
import android.os.RemoteException;
import android.test.InstrumentationTestCase;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Runs against a debug APK on a real Android device/emulator.
 * Proves that a second Android process waits on the same app-private OS file lock.
 */
public final class BuilderRecoveryGenerationBarrierProcessTest extends InstrumentationTestCase {
    public void testSecondAndroidProcessWaitsUntilFirstReleasesFileLock() throws Exception {
        Context context = getInstrumentation().getTargetContext();
        HandlerThread callbackThread = new HandlerThread("recovery-lock-test-callback");
        callbackThread.start();
        CountDownLatch connected = new CountDownLatch(1);
        CountDownLatch remoteAcquired = new CountDownLatch(1);
        CountDownLatch remoteReleased = new CountDownLatch(1);
        AtomicInteger remoteErrors = new AtomicInteger();
        Handler callback = new Handler(callbackThread.getLooper()) {
            @Override public void handleMessage(Message message) {
                if (message.what == BuilderRecoveryLockProbeService.MSG_ACQUIRED)
                    remoteAcquired.countDown();
                else if (message.what == BuilderRecoveryLockProbeService.MSG_RELEASED)
                    remoteReleased.countDown();
                else if (message.what == BuilderRecoveryLockProbeService.MSG_ERROR)
                    remoteErrors.incrementAndGet();
            }
        };
        Messenger replyTo = new Messenger(callback);
        final Messenger[] remote = new Messenger[1];
        ServiceConnection connection = new ServiceConnection() {
            @Override public void onServiceConnected(ComponentName name, IBinder binder) {
                remote[0] = new Messenger(binder);
                connected.countDown();
            }
            @Override public void onServiceDisconnected(ComponentName name) {
                remote[0] = null;
            }
        };

        ExecutorService executor = Executors.newSingleThreadExecutor();
        CountDownLatch firstAcquired = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        boolean bound = false;
        try {
            Intent intent = new Intent(context, BuilderRecoveryLockProbeService.class);
            bound = context.bindService(intent, connection, Context.BIND_AUTO_CREATE);
            assertTrue("debug probe service must bind", bound);
            assertTrue("remote process must connect", connected.await(5, TimeUnit.SECONDS));

            Future<?> first = executor.submit(() ->
                    BuilderRecoveryGenerationBarrier.withLock(context, () -> {
                        firstAcquired.countDown();
                        if (!releaseFirst.await(10, TimeUnit.SECONDS))
                            throw new IllegalStateException("Timed out waiting to release first process");
                        return null;
                    }));
            assertTrue("instrumentation process must acquire lock", firstAcquired.await(5, TimeUnit.SECONDS));

            Message acquire = Message.obtain(null, BuilderRecoveryLockProbeService.MSG_ACQUIRE);
            acquire.replyTo = replyTo;
            remote[0].send(acquire);

            assertFalse("second Android process must block while first owns lock",
                    remoteAcquired.await(500, TimeUnit.MILLISECONDS));

            releaseFirst.countDown();
            assertTrue("second Android process must acquire after release",
                    remoteAcquired.await(5, TimeUnit.SECONDS));
            Message release = Message.obtain(null, BuilderRecoveryLockProbeService.MSG_RELEASE);
            remote[0].send(release);
            assertTrue("second process must finish and release lock",
                    remoteReleased.await(5, TimeUnit.SECONDS));
            first.get(5, TimeUnit.SECONDS);
            assertEquals("remote lock worker must not report errors", 0, remoteErrors.get());
        } finally {
            releaseFirst.countDown();
            if (remote[0] != null) {
                try {
                    Message release = Message.obtain(null, BuilderRecoveryLockProbeService.MSG_RELEASE);
                    remote[0].send(release);
                } catch (RemoteException ignored) { }
            }
            if (bound) context.unbindService(connection);
            executor.shutdownNow();
            callbackThread.quitSafely();
        }
    }
}
