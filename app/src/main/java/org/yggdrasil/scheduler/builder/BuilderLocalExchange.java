package org.yggdrasil.scheduler.builder;

import android.content.Context;
import java.util.UUID;

/**
 * First local handoff demonstration. Both roles currently execute inside the app process;
 * this is not yet a pair of independently scheduled AI agents.
 */
public final class BuilderLocalExchange {
    private final BuilderEventJournal journal;

    public BuilderLocalExchange(Context context) {
        journal = new BuilderEventJournal(context.getApplicationContext());
    }

    /** Records a Mari-to-Muninn handoff and idempotent receipt for a bounded note. */
    public Result recordContinuityHandoff(String note) {
        if (note == null || note.trim().isEmpty())
            throw new IllegalArgumentException("A non-empty note is required");
        if (note.length() > 4000) throw new IllegalArgumentException("Note exceeds 4000 characters");
        long now = System.currentTimeMillis();
        String taskId = UUID.randomUUID().toString();
        String messageId = UUID.randomUUID().toString();
        journal.append("task:" + taskId, "TASK_CREATED", "MARI", now,
                "Record a continuity note for Muninn");
        BuilderMessage message = new BuilderMessage(BuilderMessage.CURRENT_SCHEMA_VERSION,
                messageId, "MARI", "MUNINN", now, taskId, "text/plain", note.trim());
        boolean firstReceipt = journal.recordMessageOnce(message, System.currentTimeMillis());
        journal.append("task-complete:" + taskId, "HANDOFF_RECORDED", "MUNINN",
                System.currentTimeMillis(), "Continuity note recorded; receipt=" + firstReceipt);
        return new Result(taskId, messageId, firstReceipt, journal.verifyChain());
    }

    public static final class Result {
        public final String taskId, messageId;
        public final boolean firstReceipt, journalIntegrityVerified;
        Result(String taskId, String messageId, boolean firstReceipt, boolean journalIntegrityVerified) {
            this.taskId=taskId; this.messageId=messageId; this.firstReceipt=firstReceipt;
            this.journalIntegrityVerified=journalIntegrityVerified;
        }
    }
}