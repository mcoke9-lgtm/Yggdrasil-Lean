package org.yggdrasil.scheduler;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.os.Build;
import androidx.core.app.NotificationCompat;
import androidx.work.Worker;
import androidx.work.WorkerParameters;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public final class SchedulerWorker extends Worker {
    private final SchedulerDb db;
    public SchedulerWorker(Context context, WorkerParameters params) { super(context, params); db = new SchedulerDb(context); }
    @Override public Result doWork() {
        boolean retry = false;
        for (SchedulerDb.Task task : db.tasks()) {
            if (!task.enabled) continue;
            try {
                ZoneId zone = ZoneId.of(task.zone);
                ZonedDateTime now = ZonedDateTime.now(zone);
                String today = now.toLocalDate().toString();
                if (now.getHour() < task.hour || (now.getHour() == task.hour && now.getMinute() < task.minute) || today.equals(task.lastDate)) continue;
                checkGithub(task, today);
            } catch (Exception e) {
                db.record(task.id, 0, "error", safe(e.getMessage()));
                db.updateCheck(task, task.lastDate, task.lastRunId, task.baseline, "Error: " + safe(e.getMessage()), task.lastCheckedAt);
                retry = true;
            }
        }
        return retry ? Result.retry() : Result.success();
    }
    private void checkGithub(SchedulerDb.Task task, String today) throws Exception {
        String checkStartedAt = Instant.now().toString();
        String endpoint = "https://api.github.com/repos/" + task.repo + "/actions/runs?branch=" + java.net.URLEncoder.encode(task.branch, "UTF-8") + "&per_page=100";
        HttpURLConnection connection = (HttpURLConnection) new URL(endpoint).openConnection();
        connection.setConnectTimeout(10000); connection.setReadTimeout(15000); connection.setRequestProperty("Accept", "application/vnd.github+json"); connection.setRequestProperty("X-GitHub-Api-Version", "2022-11-28"); connection.setRequestProperty("User-Agent", "Yggdrasil-Android-Scheduler/0.1");
        int code = connection.getResponseCode();
        if (code == 403 || code == 429) throw new java.io.IOException("GitHub rate limit or access restriction (HTTP " + code + ")");
        if (code != 200) throw new java.io.IOException("GitHub API returned HTTP " + code);
        StringBuilder json = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(connection.getInputStream()))) { String line; while ((line = reader.readLine()) != null) json.append(line); }
        finally { connection.disconnect(); }
        JSONObject root = new JSONObject(json.toString());
        JSONArray array = root.optJSONArray("workflow_runs");
        if (array == null) throw new java.io.IOException("GitHub response omitted workflow_runs");
        if (array.length() >= 100) throw new java.io.IOException("More than 100 runs returned; refusing an incomplete baseline scan");
        List<JSONObject> matches = new ArrayList<>();
        long maxId = task.lastRunId;
        for (int i=0;i<array.length();i++) {
            JSONObject run = array.getJSONObject(i);
            if (!task.branch.equals(run.optString("head_branch"))) continue;
            String name = run.optString("name");
            if (!task.workflow.equals(name)) continue;
            long id = run.optLong("id",0);
            if ("completed".equals(run.optString("status"))) {
                if (id > maxId) maxId = id;
                if (task.baseline && id > task.lastRunId) matches.add(run);
            }
        }
        if (!task.baseline) {
            db.updateCheck(task, today, maxId, true, "Baseline established", checkStartedAt);
            db.record(task.id, maxId, "baseline", "Recorded existing workflow runs; no historic failures alerted");
            return;
        }
        matches.sort(Comparator.comparingLong(x -> x.optLong("id",0)));
        for (JSONObject run : matches) {
            String conclusion = run.optString("conclusion", "unknown");
            if ("failure".equals(conclusion) || "timed_out".equals(conclusion) || "startup_failure".equals(conclusion) || "action_required".equals(conclusion)) {
                String detail = run.optString("name") + " #" + run.optLong("id") + " · " + conclusion + " · " + run.optString("html_url");
                db.record(task.id, run.optLong("id"), "failed", detail);
                notifyFailure(task.name, detail);
            }
        }
        db.updateCheck(task, today, maxId, true, matches.isEmpty() ? "Checked; no new runs" : "Checked; " + matches.size() + " new completed run(s)", checkStartedAt);
        db.record(task.id, maxId, "checked", "Daily GitHub workflow check completed");
    }
    private void notifyFailure(String title, String detail) {
        Context context = getApplicationContext();
        NotificationManager manager = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        String channel = "scheduler-failures";
        if (Build.VERSION.SDK_INT >= 26) manager.createNotificationChannel(new NotificationChannel(channel, "Scheduler failures", NotificationManager.IMPORTANCE_DEFAULT));
        android.app.Notification notification = new NotificationCompat.Builder(context, channel).setSmallIcon(android.R.drawable.stat_notify_error).setContentTitle("Scheduled workflow failed: " + title).setContentText(detail).setStyle(new NotificationCompat.BigTextStyle().bigText(detail)).setAutoCancel(true).build();
        manager.notify((int)(System.currentTimeMillis() & 0x7fffffff), notification);
    }
    private String safe(String value) { if (value == null) return "unknown error"; return value.length() > 300 ? value.substring(0,300) : value; }
}
