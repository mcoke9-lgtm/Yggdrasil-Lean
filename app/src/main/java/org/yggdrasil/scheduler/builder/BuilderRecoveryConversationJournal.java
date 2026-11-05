package org.yggdrasil.scheduler.builder;

import android.content.Context;
import java.util.List;

/**
 * Generation-aware canonical conversation journal.
 *
 * Before a recovery generation is activated, operations use the existing canonical
 * database. Once one is active, every operation is routed to its matching immutable
 * baseline plus append-only overlay while holding the cross-process generation barrier.
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
        return withJournal(BuilderRecoveryOverlayAccess.Session::listThreadIds);
    }

    @Override public org.json.JSONObject exportSnapshot() {
        return withJournal(BuilderRecoveryOverlayAccess.Session::exportConversationSnapshot);
    }

    @Override public void close() {
        // Each operation owns and closes its short-lived database helper.
    }

    private <T> T withJournal(JournalOperation<T> operation) {
        return BuilderRecoveryGenerationBarrier.withLock(app, () -> {
            BuilderRecoveryActivation.ActivePair active = BuilderRecoveryActivation.getActive(app);
            if (active == null) {
                BuilderConversationStore legacy = new BuilderConversationStore(app);
                try {
                    return operation.run(legacy);
                } finally {
                    legacy.close();
                }
            }
            return BuilderRecoveryOverlayAccess.withActive(app, operation::run);
        });
    }

    private interface JournalOperation<T> {
        T run(BuilderRecoveryOverlayAccess.Session session);
    }
}
