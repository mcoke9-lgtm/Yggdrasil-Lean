package org.yggdrasil.scheduler.builder;

import android.content.Context;
import org.json.JSONObject;
import java.util.List;

/**
 * Generation-scoped overlay access.
 *
 * The active pointer is resolved only after acquiring the cross-process barrier.
 * The overlay is opened, identity-checked, used, and closed before releasing that
 * barrier. A Session exposes typed operations only; it never exposes SQLite handles.
 */
public final class BuilderRecoveryOverlayAccess {
    private BuilderRecoveryOverlayAccess() { }

    public interface Operation<T> {
        T run(Session session) throws Exception;
    }

    public static <T> T withActive(Context context, Operation<T> operation) {
        if (context == null) throw new IllegalArgumentException("context is required");
        if (operation == null) throw new IllegalArgumentException("operation is required");
        Context app = context.getApplicationContext();
        return BuilderRecoveryGenerationBarrier.withLock(app, () -> {
            BuilderRecoveryActivation.ActivePair active = BuilderRecoveryActivation.getActive(app);
            if (active == null)
                throw new IllegalStateException("No active recovery generation is selected");
            BuilderRecoveryOverlayStore store = BuilderRecoveryOverlayStore.openExisting(app,
                    active.conversationDatabaseName, active.subjectDatabaseName,
                    active.generationId, active.conversationDataSha256,
                    active.subjectDataSha256);
            Session session = new Session(store);
            try {
                BuilderRecoveryOverlayStore.Identity identity = store.identity();
                if (!active.generationId.equals(identity.generationId)
                        || !active.conversationDataSha256.equals(identity.conversationBaselineSha256)
                        || !active.subjectDataSha256.equals(identity.subjectBaselineSha256))
                    throw new IllegalStateException("Active overlay identity does not match the active generation");
                return operation.run(session);
            } finally {
                session.invalidate();
                store.close();
            }
        });
    }

    /** A callback-scoped, typed view. Retaining it after the callback is rejected. */
    public static final class Session {
        private final BuilderRecoveryOverlayStore store;
        private final Thread owner;
        private boolean active = true;

        private Session(BuilderRecoveryOverlayStore store) {
            this.store = store;
            this.owner = Thread.currentThread();
        }

        public BuilderRecoveryOverlayStore.Identity identity() {
            ensureActive();
            return store.identity();
        }

        public void append(BuilderConversationProtocol.Turn turn) {
            ensureActive();
            store.append(turn);
        }

        public void appendAll(List<BuilderSubjectThreadIndex.Event> events) {
            ensureActive();
            store.appendAll(events);
        }

        public List<BuilderConversationProtocol.Turn> readThread(String threadId) {
            ensureActive();
            return store.readThread(threadId);
        }

        public List<String> listThreadIds() {
            ensureActive();
            return store.listThreadIds();
        }

        public List<BuilderSubjectThreadIndex.Event> readSubjectThread(String threadId) {
            ensureActive();
            return store.readSubjectThread(threadId);
        }

        public JSONObject exportConversationSnapshot() {
            ensureActive();
            return store.exportConversationSnapshot();
        }

        public JSONObject exportSubjectSnapshot() {
            ensureActive();
            return store.exportSubjectSnapshot();
        }

        public JSONObject exportMergedSnapshots() {
            ensureActive();
            return store.exportMergedSnapshots();
        }

        private void ensureActive() {
            if (!active || Thread.currentThread() != owner)
                throw new IllegalStateException("Recovery overlay session is no longer active on this thread");
        }

        private void invalidate() {
            active = false;
        }
    }
}
