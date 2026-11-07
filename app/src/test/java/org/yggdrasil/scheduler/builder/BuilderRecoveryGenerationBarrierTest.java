package org.yggdrasil.scheduler.builder;

import android.content.Context;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

import java.io.File;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
public class BuilderRecoveryGenerationBarrierTest {
    private Context context() {
        return RuntimeEnvironment.getApplication();
    }

    @Test public void createsPermanentAppPrivateLockFile() {
        BuilderRecoveryGenerationBarrier.withLock(context(), () -> null);
        File lock = new File(context().getFilesDir(),
                BuilderRecoveryGenerationBarrier.lockFileNameForTests());
        assertTrue(lock.isFile());
        assertTrue(lock.length() == 0L);
        BuilderRecoveryGenerationBarrier.withLock(context(), () -> null);
        assertTrue("lock file must be retained, not replaced or deleted", lock.isFile());
    }

    @Test public void nestedSameThreadOperationReusesHeldFileLock() {
        String result = BuilderRecoveryGenerationBarrier.withLock(context(), () ->
                "outer:" + BuilderRecoveryGenerationBarrier.withLock(context(), () -> "inner"));
        assertEquals("outer:inner", result);
    }

    @Test public void serializesThreadsInOneProcess() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch firstEntered = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        CountDownLatch secondEntered = new CountDownLatch(1);
        try {
            Future<?> first = pool.submit(() -> BuilderRecoveryGenerationBarrier.withLock(context(), () -> {
                firstEntered.countDown();
                assertTrue(releaseFirst.await(5, TimeUnit.SECONDS));
                return null;
            }));
            assertTrue(firstEntered.await(5, TimeUnit.SECONDS));
            Future<?> second = pool.submit(() -> BuilderRecoveryGenerationBarrier.withLock(context(), () -> {
                secondEntered.countDown();
                return null;
            }));
            assertFalse("second thread must wait for first", secondEntered.await(150, TimeUnit.MILLISECONDS));
            releaseFirst.countDown();
            first.get(5, TimeUnit.SECONDS);
            second.get(5, TimeUnit.SECONDS);
            assertEquals(0L, secondEntered.getCount());
        } finally {
            releaseFirst.countDown();
            pool.shutdownNow();
        }
    }

    @Test public void releasesBothLocksWhenOperationThrows() {
        try {
            BuilderRecoveryGenerationBarrier.withLock(context(), () -> {
                throw new IllegalArgumentException("injected operation failure");
            });
            fail("expected operation failure");
        } catch (IllegalStateException expected) {
            assertTrue(expected.getCause() instanceof IllegalArgumentException);
        }
        assertEquals("subsequent operation must acquire cleanly", "ok",
                BuilderRecoveryGenerationBarrier.withLock(context(), () -> "ok"));
    }

    @Test public void rejectsNonFileLockPathWithoutRunningOperation() {
        File lock = new File(context().getFilesDir(),
                BuilderRecoveryGenerationBarrier.lockFileNameForTests());
        assertTrue(lock.mkdir());
        AtomicInteger calls = new AtomicInteger();
        try {
            BuilderRecoveryGenerationBarrier.withLock(context(), () -> {
                calls.incrementAndGet();
                return null;
            });
            fail("expected lock-path failure");
        } catch (IllegalStateException expected) {
            assertEquals(0, calls.get());
        } finally {
            assertTrue(lock.delete());
        }
    }
}
