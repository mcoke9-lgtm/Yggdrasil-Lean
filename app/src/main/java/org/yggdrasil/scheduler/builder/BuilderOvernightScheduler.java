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

        Context app = context.getApplicationContext();
        String runId = UUID.randomUUID().toString();
        String threadId = UUID.randomUUID().toString();
        String workName = workName(runId);
        BuilderOvernightPayloadCipher.Payload sealed;
        try { sealed = BuilderOvernightPayloadCipher.encrypt(taskPrompt.trim()); }
        catch (Exception failure) { throw new IllegalStateException("Could not protect scheduled prompt", failure); }
        Data input = new Data.Builder()
                .putString(BuilderOvernightWorker.KEY_RUN_ID, runId)
                .putString(BuilderOvernightWorker.KEY_THREAD_ID, threadId)
                .putString(BuilderOvernightWorker.KEY_TASK_PROMPT_CIPHERTEXT, sealed.ciphertext)
                .putString(BuilderOvernightWorker.KEY_TASK_PROMPT_IV, sealed.iv)
                .putString(BuilderOvernightWorker.KEY_DISCLOSURE_HASH, disclosureFingerprint)
                .putLong(BuilderOvernightWorker.KEY_CYCLE_INTERVAL, DEFAULT_CYCLE_INTERVAL_MILLIS)
                .build();
        Constraints constraints = new Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED).build();
        OneTimeWorkRequest request = new OneTimeWorkRequest.Builder(BuilderOvernightWorker.class)
                .setInputData(input).setConstraints(constraints)
                .setInitialDelay(initialDelayMillis, TimeUnit.MILLISECONDS)
                .addTag("nine-builders-overnight").build();

        BuilderOvernightRunStore store = new BuilderOvernightRunStore(app);
        store.createScheduled(runId, threadId, taskPrompt.trim(), System.currentTimeMillis());
        boolean saved = app.getSharedPreferences("builder_overnight_runs", Context.MODE_PRIVATE)
                .edit().putString("last_scheduled_run_id", runId).commit();
        if (!saved) {
            store.setStatus(runId, "FAILED", "Could not persist scheduled run identifier.",
                    System.currentTimeMillis());
            throw new IllegalStateException("Could not persist scheduled run identifier");
        }
        try {
            WorkManager.getInstance(app).enqueueUniqueWork(workName, ExistingWorkPolicy.KEEP, request);
        } catch (RuntimeException failure) {
            store.setStatus(runId, "FAILED", "WorkManager rejected the scheduled request.",
                    System.currentTimeMillis());
            throw failure;
        }
        return new ScheduledRun(runId, threadId, workName);
    }

    public static boolean cancelLast(Context context) {
        if (context == null) throw new IllegalArgumentException("Context is required");
        Context app = context.getApplicationContext();
        String runId = app.getSharedPreferences("builder_overnight_runs", Context.MODE_PRIVATE)
                .getString("last_scheduled_run_id", null);
        if (runId == null || runId.trim().isEmpty()) return false;
        BuilderOvernightRunStore store = new BuilderOvernightRunStore(app);
        org.json.JSONObject state = store.read(runId);
        if (state != null) {
            String status = state.optString("status");
            if (!"RUNNING".equals(status) && !"SCHEDULED".equals(status)) return false;
            store.setStatus(runId, "CANCELLED",
                    "Cancelled by the user from the Builder Conversation screen.",
                    System.currentTimeMillis());
        }
        WorkManager.getInstance(app).cancelUniqueWork(workName(runId));
        return true;
    }

    public static org.json.JSONObject latestState(Context context) {
        if (context == null) throw new IllegalArgumentException("Context is required");
        Context app = context.getApplicationContext();
        String runId = app.getSharedPreferences("builder_overnight_runs", Context.MODE_PRIVATE)
                .getString("last_scheduled_run_id", null);
        if (runId == null) return null;
        return new BuilderOvernightRunStore(app).read(runId);
    }

    static String workName(String runId) { return "nine-builders-overnight-" + runId; }
}