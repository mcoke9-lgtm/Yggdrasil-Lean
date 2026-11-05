package org.yggdrasil.scheduler.builder;

import android.content.Context;
import androidx.work.Constraints;
import androidx.work.Data;
import androidx.work.ExistingWorkPolicy;
import androidx.work.NetworkType;
import androidx.work.OneTimeWorkRequest;
import androidx.work.WorkManager;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/** Durable WorkManager scheduling for one consent-bound, eight-hour maximum Builder run. */
public final class BuilderOvernightScheduler {
    public static final long DEFAULT_CYCLE_INTERVAL_MILLIS = 15L * 60L * 1000L;
    private static final int MAX_PROMPT_CHARS = 4000;
    private BuilderOvernightScheduler() {}

    public static final class ScheduledRun {
        public final String runId;
        public final String threadId;
        public final String uniqueWorkName;
        ScheduledRun(String runId, String threadId, String uniqueWorkName) {
            this.runId = runId;
            this.threadId = threadId;
            this.uniqueWorkName = uniqueWorkName;
        }
    }

    public static ScheduledRun schedule(Context context, String taskPrompt,
            String disclosureFingerprint, long initialDelayMillis) {
        if (context == null) throw new IllegalArgumentException("Context is required");
        if (taskPrompt == null || taskPrompt.trim().isEmpty()
                || taskPrompt.trim().length() > MAX_PROMPT_CHARS)
            throw new IllegalArgumentException("Task prompt must contain 1–4000 characters");
        if (disclosureFingerprint == null || !disclosureFingerprint.matches("[a-f0-9]{64}"))
            throw new IllegalArgumentException("A SHA-256 disclosure fingerprint is required");
        if (initialDelayMillis < 0 || initialDelayMillis > TimeUnit.DAYS.toMillis(7))
            throw new IllegalArgumentException("Initial delay must be between now and seven days");

        String runId = UUID.randomUUID().toString();
        String threadId = UUID.randomUUID().toString();
        String workName = workName(runId);
        Data input = new Data.Builder()
                .putString(BuilderOvernightWorker.KEY_RUN_ID, runId)
                .putString(BuilderOvernightWorker.KEY_THREAD_ID, threadId)
                .putString(BuilderOvernightWorker.KEY_TASK_PROMPT, taskPrompt.trim())
                .putString(BuilderOvernightWorker.KEY_DISCLOSURE_HASH, disclosureFingerprint)
                .putLong(BuilderOvernightWorker.KEY_CYCLE_INTERVAL, DEFAULT_CYCLE_INTERVAL_MILLIS)
                .build();
        Constraints constraints = new Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED).build();
        OneTimeWorkRequest request = new OneTimeWorkRequest.Builder(BuilderOvernightWorker.class)
                .setInputData(input).setConstraints(constraints)
                .setInitialDelay(initialDelayMillis, TimeUnit.MILLISECONDS)
                .addTag("nine-builders-overnight").build();
        WorkManager.getInstance(context.getApplicationContext())
                .enqueueUniqueWork(workName, ExistingWorkPolicy.KEEP, request);
        return new ScheduledRun(runId, threadId, workName);
    }

    static String workName(String runId) { return "nine-builders-overnight-" + runId; }
}