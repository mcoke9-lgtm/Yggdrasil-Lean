package org.yggdrasil.scheduler.builder;

import android.content.Context;
import java.util.List;

/**
 * Generation-aware canonical conversation journal.
 * Before activation it uses the existing canonical database; after activation it
 * routes every operation to the matching immutable baseline plus append-only overlay.
 */
public final class BuilderRecoveryConversationJournal implements BuilderConversationJournal {
    private final Context app;

    public BuilderRecoveryConversationJournal(Context context) {
        if (context == null) throw new IllegalArgumentException("context is required");
        this.app = context.getApplicationContext();
    }

    @Override public void append(BuilderConversationProtocol.Turn turn) {
        withJournal(journal -> { journal.append(turn); return null; });
    }

    @Override public List<BuilderConversationProtocol.Turn> readThread(String threadId) {
        return withJournal(journal -> journal.readThread(threadId));
    }

    @Override public List<String> listThreadIds() {
        return withJournal(JournalView::listThreadIds);
    }

    @Override public org.json.JSONObject exportSnapshot() {
        return withJournal(JournalView::exportSnapshot);
    }

    @Override public void close() { }

    private <T> T withJournal(JournalOperation<T> operation) {
        return BuilderRecoveryGenerationBarrier.withLock(app, () -> {
            BuilderRecoveryActivation.ActivePair active = BuilderRecoveryActivation.getActive(app);
            if (active == null) {
                BuilderConversationStore legacy = new BuilderConversationStore(app);
                try {
                    return operation.run(new StoreView(legacy));
                } finally {
                    legacy.close();
                }
            }
            return BuilderRecoveryOverlayAccess.withActive(app,
                    session -> operation.run(new SessionView(session)));
        });
    }

    private interface JournalOperation<T> { T run(JournalView journal); }

    private interface JournalView {
        void append(BuilderConversationProtocol.Turn turn);
        List<BuilderConversationProtocol.Turn> readThread(String threadId);
        List<String> listThreadIds();
        org.json.JSONObject exportSnapshot();
    }

    private static final class StoreView implements JournalView {
        private final BuilderConversationStore store;
        StoreView(BuilderConversationStore store) { this.store = store; }
        @Override public void append(BuilderConversationProtocol.Turn turn) { store.append(turn); }
        @Override public List<BuilderConversationProtocol.Turn> readThread(String id) { return store.readThread(id); }
        @Override public List<String> listThreadIds() { return store.listThreadIds(); }
        @Override public org.json.JSONObject exportSnapshot() { return store.exportSnapshot(); }
    }

    private static final class SessionView implements JournalView {
        private final BuilderRecoveryOverlayAccess.Session session;
        SessionView(BuilderRecoveryOverlayAccess.Session session) { this.session = session; }
        @Override public void append(BuilderConversationProtocol.Turn turn) { session.append(turn); }
        @Override public List<BuilderConversationProtocol.Turn> readThread(String id) { return session.readThread(id); }
        @Override public List<String> listThreadIds() { return session.listThreadIds(); }
        @Override public org.json.JSONObject exportSnapshot() { return session.exportConversationSnapshot(); }
    }
}
