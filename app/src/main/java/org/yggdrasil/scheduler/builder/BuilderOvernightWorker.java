package org.yggdrasil.scheduler.builder;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import androidx.work.Data;
import androidx.work.ExistingWorkPolicy;
import androidx.work.ForegroundInfo;
import androidx.work.OneTimeWorkRequest;
import androidx.work.WorkManager;
import androidx.work.Worker;
import androidx.work.WorkerParameters;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/** Executes one all-nine Builder cycle, then schedules the next inside an eight-hour maximum window. */
public final class BuilderOvernightWorker extends Worker {
    static final String KEY_RUN_ID = "builder_run_id";
    static final String KEY_THREAD_ID = "builder_thread_id";
    static final String KEY_TASK_PROMPT_CIPHERTEXT = "builder_task_prompt_ciphertext";
    static final String KEY_TASK_PROMPT_IV = "builder_task_prompt_iv";
    static final String KEY_DISCLOSURE_HASH = "builder_disclosure_hash";
    static final String KEY_CYCLE_INTERVAL = "builder_cycle_interval_ms";
    private static final String CHANNEL_ID = "nine_builders_overnight";
    private static final int NOTIFICATION_ID = 9401;
    private static final long MINIMUM_CYCLE_BUDGET_MILLIS = 12L * 60L * 1000L;

    public BuilderOvernightWorker(Context context, WorkerParameters parameters) {
        super(context, parameters);
    }

    @Override public Result doWork() {
        String runId = getInputData().getString(KEY_RUN_ID);
        String threadId = getInputData().getString(KEY_THREAD_ID);
        String taskPrompt;
        try {
            taskPrompt = BuilderOvernightPayloadCipher.decrypt(
                    getInputData().getString(KEY_TASK_PROMPT_CIPHERTEXT),
                    getInputData().getString(KEY_TASK_PROMPT_IV));
        } catch (Exception failure) { return Result.failure(); }
        String expectedDisclosure = getInputData().getString(KEY_DISCLOSURE_HASH);
        long interval = getInputData().getLong(KEY_CYCLE_INTERVAL,
                BuilderOvernightScheduler.DEFAULT_CYCLE_INTERVAL_MILLIS);
        if (blank(runId) || blank(threadId) || blank(taskPrompt) || blank(expectedDisclosure))
            return Result.failure();

        BuilderOvernightRunStore runStore = new BuilderOvernightRunStore(getApplicationContext());
        long now = System.currentTimeMillis();
        org.json.JSONObject state;
        try {
            state = runStore.beginOrRecover(runId, threadId, taskPrompt, now);
            if (!"RUNNING".equals(state.optString("status"))) return Result.failure();
            if (now >= state.optLong("deadline_epoch_millis")) {
                runStore.setStatus(runId, "EXPIRED", "Eight-hour execution window elapsed.", now);
                return Result.success();
            }
            setForegroundAsync(foregroundInfo(runId,
                    "Preparing Builder cycle " + (state.optInt("completed_cycles") + 1)))
                    .get(15, TimeUnit.SECONDS);
        } catch (Exception failure) {
            return Result.failure();
        }

        BuilderConversationJournal journal = null;
        try {
            BuilderRoleProviderSettings settings =
                    new BuilderRoleProviderSettings(getApplicationContext());
            List<BuilderRole> roles = new ArrayList<>();
            for (BuilderRole role : BuilderRole.values()) roles.add(role);
            settings.requireConfigured(roles);
            settings.requireIndependentBaldrRoute();

            List<BuilderDisclosureFingerprint.Route> routes = new ArrayList<>();
            for (BuilderRole role : roles) {
                SecureBuilderProviderSettings.Configuration config = settings.configuration(role);
                routes.add(new BuilderDisclosureFingerprint.Route(role.name(),
                        config.providerId, config.endpoint, config.model));
            }
            String actualDisclosure = BuilderDisclosureFingerprint.digest(
                    BuilderDisclosureFingerprint.createOvernight(routes, taskPrompt));
            if (!actualDisclosure.equals(expectedDisclosure)) {
                runStore.setStatus(runId, "FAILED",
                        "Configured routes or task prompt differ from the one-time approved schedule.", now);
                return Result.failure();
            }

            long available = state.optLong("deadline_epoch_millis") - System.currentTimeMillis();
            if (available < MINIMUM_CYCLE_BUDGET_MILLIS) {
                runStore.setStatus(runId, "COMPLETED", "No further full cycle fits in the eight-hour window.", System.currentTimeMillis());
                return Result.success();
            }
            state = runStore.beginCycle(runId, now);
            int cycle = state.optInt("current_cycle");
            setForegroundAsync(foregroundInfo(runId, "Cycle " + cycle + " · nine Builders working"))
                    .get(15, TimeUnit.SECONDS);

            BuilderModelResponder responder = new BuilderModelResponder(settings.buildRouter(),
                    2048, BuilderOperationalLimits.load(getApplicationContext()).toPromptJson());
            journal = new BuilderRuntimeJournalClient(getApplicationContext());
            BuilderConversationCoordinator coordinator =
                    new BuilderConversationCoordinator(journal, responder);
            String cyclePrompt = "NINE_BUILDERS_OVERNIGHT_RUN cycle=" + cycle + "\n"
                    + "Continue the scheduled task below using the complete history in this dedicated run thread. "
                    + "Do not repeat completed work. Select the next concrete, useful step; make incremental progress; "
                    + "record evidence, uncertainty, decisions needed, and a checkpoint. The eight operational Builders "
                    + "contribute first. Baldr independently reviews their accumulated work last. Do not claim an action "
                    + "was performed unless it was actually completed.\n\nTASK:\n" + taskPrompt;
            BuilderConversationCoordinator.Result result = coordinator.submit(threadId, cyclePrompt, roles);
            if (isStopped()) return Result.failure();
            if (result.interrupted || result.failedRoles.contains(BuilderRole.BALDR)) {
                runStore.setStatus(runId, "INTERRUPTED", "Baldr review incomplete; manual inspection required.", System.currentTimeMillis());
                return Result.failure();
            }
            String summary = "cycle=" + cycle + "; replies=" + (result.appendedTurns.size() - 1)
                    + "; failed_roles=" + result.failedRoles
                    + "; unrouted_recipients=" + result.unroutedRecipients
                    + "; interrupted=" + result.interrupted;
            state = runStore.finishCycle(runId, summary, System.currentTimeMillis());

            long remaining = state.optLong("deadline_epoch_millis") - System.currentTimeMillis();
            if (remaining > interval && !isStopped()) enqueueContinuation(runId, interval);
            else runStore.setStatus(runId, "COMPLETED",
                    "Eight-hour window ended or no further cycle fits.", System.currentTimeMillis());
            return Result.success();
        } catch (Exception failure) {
            if (!isStopped()) {
                try {
                    runStore.setStatus(runId, "INTERRUPTED",
                            "Cycle failed or host was interrupted: " + safeMessage(failure),
                            System.currentTimeMillis());
                } catch (Exception ignored) { }
            }
            return Result.failure();
        } finally {
            if (journal != null) journal.close();
        }
    }

    static OneTimeWorkRequest continuationRequest(Data inputData, long interval) {
        return new OneTimeWorkRequest.Builder(BuilderOvernightWorker.class)
                .setInputData(inputData)
                .setConstraints(new Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setInitialDelay(interval, TimeUnit.MILLISECONDS)
                .addTag("nine-builders-overnight").build();
    }

    private void enqueueContinuation(String runId, long interval) {
        OneTimeWorkRequest next = continuationRequest(getInputData(), interval);
        WorkManager.getInstance(getApplicationContext()).enqueueUniqueWork(
                BuilderOvernightScheduler.workName(runId),
                ExistingWorkPolicy.APPEND_OR_REPLACE, next);
    }

    private ForegroundInfo foregroundInfo(String runId, String message) {
        Context context = getApplicationContext();
        NotificationManager manager = (NotificationManager)
                context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= 26) {
            manager.createNotificationChannel(new NotificationChannel(CHANNEL_ID,
                    "Nine Builders scheduled work", NotificationManager.IMPORTANCE_LOW));
        }
        Intent cancel = new Intent(context, BuilderOvernightCancelReceiver.class)
                .setAction(BuilderOvernightCancelReceiver.ACTION_CANCEL)
                .putExtra(BuilderOvernightCancelReceiver.EXTRA_RUN_ID, runId);
        PendingIntent cancelPending = PendingIntent.getBroadcast(context, runId.hashCode(),
                cancel, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder builder = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(context, CHANNEL_ID) : new Notification.Builder(context);
        Notification notification = builder.setSmallIcon(android.R.drawable.ic_popup_sync)
                .setContentTitle("Nine Builders overnight run")
                .setContentText(message).setOngoing(true)
                .addAction(android.R.drawable.ic_media_pause, "Stop run", cancelPending).build();
        if (Build.VERSION.SDK_INT >= 34) {
            return new ForegroundInfo(NOTIFICATION_ID, notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        }
        if (Build.VERSION.SDK_INT >= 29) return new ForegroundInfo(NOTIFICATION_ID, notification, 0);
        return new ForegroundInfo(NOTIFICATION_ID, notification);
    }

    private static boolean blank(String value) { return value == null || value.trim().isEmpty(); }
    private static String safeMessage(Exception failure) {
        String message = failure.getMessage();
        String value = message == null ? failure.getClass().getSimpleName() : message;
        value = value.replace('\r', ' ').replace('\n', ' ');
        return value.substring(0, Math.min(500, value.length()));
    }
}