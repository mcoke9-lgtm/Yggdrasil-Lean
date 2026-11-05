package org.yggdrasil.scheduler.builder;

import android.content.Context;

import java.io.File;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.StandardOpenOption;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Cross-process serialization primitive for recovery-generation operations.
 *
 * The lock file is permanent and app-private. Every participating process opens
 * the same file and takes an exclusive OS lock. A process-local reentrant lock
 * serializes threads before they attempt the OS lock; same-thread nested calls
 * reuse the already-held file lock rather than triggering OverlappingFileLockException.
 *
 * Activation, guarded selection, and the canonical conversation/subject writer adapters use this barrier.
 * It does not itself activate a generation or trigger startup migration.
 */
public final class BuilderRecoveryGenerationBarrier {
    private static final String LOCK_FILE_NAME = "builder-recovery-generation.lock";
    private static final ReentrantLock PROCESS_LOCK = new ReentrantLock();
    private static final ThreadLocal<HeldLock> HELD_LOCK = new ThreadLocal<>();

    private BuilderRecoveryGenerationBarrier() { }

    /** A complete generation-sensitive operation. Keep the callback's scope narrow. */
    public interface Operation<T> {
        T run() throws Exception;
    }

    /**
     * Runs an operation while holding the process lock and exclusive OS file lock.
     * Interrupted acquisition restores the interrupt flag and fails closed.
     */
    public static <T> T withLock(Context context, Operation<T> operation) {
        if (context == null) throw new IllegalArgumentException("context is required");
        if (operation == null) throw new IllegalArgumentException("operation is required");

        boolean processLockAcquired = false;
        HeldLock held = null;
        try {
            PROCESS_LOCK.lockInterruptibly();
            processLockAcquired = true;

            held = HELD_LOCK.get();
            if (held == null) {
                File directory = context.getApplicationContext().getFilesDir();
                if (directory == null || !directory.isDirectory())
                    throw new IllegalStateException("App-private files directory is unavailable");
                File lockFile = new File(directory, LOCK_FILE_NAME);
                if (lockFile.exists() && !lockFile.isFile())
                    throw new IllegalStateException("Recovery generation lock path is not a regular file");

                FileChannel channel = FileChannel.open(lockFile.toPath(),
                        StandardOpenOption.CREATE, StandardOpenOption.WRITE);
                FileLock fileLock = null;
                try {
                    fileLock = channel.lock();
                    held = new HeldLock(channel, fileLock);
                    HELD_LOCK.set(held);
                } catch (Exception acquisitionFailure) {
                    if (fileLock != null) {
                        try { fileLock.release(); } catch (Exception ignored) { }
                    }
                    try { channel.close(); } catch (Exception ignored) { }
                    throw acquisitionFailure;
                }
            }

            held.depth++;
            try {
                return operation.run();
            } finally {
                held.depth--;
                if (held.depth == 0) {
                    HELD_LOCK.remove();
                    held.release();
                }
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting for recovery generation lock", interrupted);
        } catch (Exception failure) {
            if (failure instanceof IllegalStateException)
                throw (IllegalStateException) failure;
            throw new IllegalStateException("Recovery generation operation failed while holding its lock", failure);
        } finally {
            if (processLockAcquired) PROCESS_LOCK.unlock();
        }
    }

    /** Exposed only as a stable filename for diagnostics and tests; never delete it. */
    static String lockFileNameForTests() {
        return LOCK_FILE_NAME;
    }

    private static final class HeldLock {
        final FileChannel channel;
        final FileLock fileLock;
        int depth;

        HeldLock(FileChannel channel, FileLock fileLock) {
            this.channel = channel;
            this.fileLock = fileLock;
        }

        void release() {
            Exception failure = null;
            try {
                if (fileLock.isValid()) fileLock.release();
            } catch (Exception e) {
                failure = e;
            }
            try {
                channel.close();
            } catch (Exception e) {
                if (failure == null) failure = e;
                else failure.addSuppressed(e);
            }
            if (failure != null)
                throw new IllegalStateException("Could not release recovery generation lock cleanly", failure);
        }
    }
}
