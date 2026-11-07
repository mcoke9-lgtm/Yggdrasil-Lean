package org.yggdrasil.scheduler.builder;

import android.content.Context;
import java.io.File;

/**
 * Read-only startup decision for the conversation and subject-index stores.
 *
 * The selector never creates, migrates, renames, or deletes a database. If no
 * activation manifest exists, the established canonical stores remain selected.
 * If a manifest exists, BuilderRecoveryActivation must validate the complete
 * active pair before either staged database name is returned.
 *
 * Invalid recovery metadata is deliberately propagated as an error: callers
 * must not silently fall back to canonical history or guess a previous generation.
 */
public final class BuilderRecoveryStartupSelector {
    public static final String CANONICAL_CONVERSATION_DATABASE = "builder-conversations.db";
    public static final String CANONICAL_SUBJECT_DATABASE = "builder-subject-thread-index.db";

    public enum Source { CANONICAL, RECOVERY }

    public static final class Selection {
        public final Source source;
        public final String conversationDatabaseName;
        public final String subjectDatabaseName;
        public final String generationId;
        public final String previousGenerationId;

        private Selection(Source source, String conversationDatabaseName,
                          String subjectDatabaseName, String generationId,
                          String previousGenerationId) {
            this.source = source;
            this.conversationDatabaseName = conversationDatabaseName;
            this.subjectDatabaseName = subjectDatabaseName;
            this.generationId = generationId;
            this.previousGenerationId = previousGenerationId;
        }

        public boolean usesRecovery() { return source == Source.RECOVERY; }
    }

    private BuilderRecoveryStartupSelector() { }

    /**
     * Resolves one coherent store pair. No manifest means canonical stores;
     * a present manifest requires a valid pointer, descriptor, and both databases.
     */
    public static Selection resolve(Context context) {
        if (context == null) throw new IllegalArgumentException("context is required");
        Context app = context.getApplicationContext();
        return BuilderRecoveryGenerationBarrier.withLock(app, () -> resolveLocked(app));
    }

    private static Selection resolveLocked(Context app) {
        File manifest = new File(app.getFilesDir(), "builder-recovery-active.json");
        if (!manifest.exists()) {
            return new Selection(Source.CANONICAL,
                    CANONICAL_CONVERSATION_DATABASE, CANONICAL_SUBJECT_DATABASE,
                    null, null);
        }

        // getActive() validates the manifest checksum, descriptor, both database
        // names, row counts, and exact canonical record digests. Do not catch its
        // exception here: an invalid pointer must stop selection, not trigger fallback.
        BuilderRecoveryActivation.ActivePair active =
                BuilderRecoveryActivation.getActive(app);
        if (active == null)
            throw new IllegalStateException("Recovery manifest exists but no active pair was selected");

        return new Selection(Source.RECOVERY,
                active.conversationDatabaseName, active.subjectDatabaseName,
                active.generationId, active.previousGenerationId);
    }
}
