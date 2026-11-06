package org.yggdrasil.scheduler;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import java.util.ArrayList;
import java.util.List;

public final class SchedulerDb extends SQLiteOpenHelper {
    public static final String DB_NAME = "yggdrasil_scheduler.db";
    public SchedulerDb(Context context) { super(context, DB_NAME, null, 3); }
    @Override public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE tasks (id TEXT PRIMARY KEY, name TEXT NOT NULL, repo TEXT NOT NULL, branch TEXT NOT NULL, workflow TEXT NOT NULL, hour INTEGER NOT NULL, minute INTEGER NOT NULL, zone TEXT NOT NULL, enabled INTEGER NOT NULL DEFAULT 1, last_local_date TEXT, last_seen_run_id INTEGER NOT NULL DEFAULT 0, baseline INTEGER NOT NULL DEFAULT 0, last_status TEXT NOT NULL DEFAULT 'Waiting', updated_at TEXT NOT NULL, last_checked_at TEXT, days_of_week TEXT NOT NULL DEFAULT '1,2,3,4,5,6,7')");
        db.execSQL("CREATE TABLE runs (id INTEGER PRIMARY KEY AUTOINCREMENT, task_id TEXT NOT NULL, run_id INTEGER, status TEXT NOT NULL, detail TEXT, created_at TEXT NOT NULL)");
    }
    @Override public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) { if (oldVersion < 2) db.execSQL("ALTER TABLE tasks ADD COLUMN last_checked_at TEXT"); if (oldVersion < 3) db.execSQL("ALTER TABLE tasks ADD COLUMN days_of_week TEXT NOT NULL DEFAULT '1,2,3,4,5,6,7'"); }
    public synchronized void addTask(String id, String name, String repo, String branch, String workflow, int hour, int minute, String zone, String days) {
        ContentValues v = new ContentValues(); v.put("id", id); v.put("name", name); v.put("repo", repo); v.put("branch", branch); v.put("workflow", workflow); v.put("hour", hour); v.put("minute", minute); v.put("zone", zone); v.put("days_of_week", days); v.put("enabled", 1); v.put("updated_at", java.time.Instant.now().toString());
        getWritableDatabase().insertOrThrow("tasks", null, v);
    }
    public synchronized List<Task> tasks() {
        List<Task> out = new ArrayList<>();
        try (Cursor c = getReadableDatabase().rawQuery("SELECT id,name,repo,branch,workflow,hour,minute,zone,enabled,last_local_date,last_seen_run_id,baseline,last_status,last_checked_at,days_of_week FROM tasks ORDER BY name", null)) {
            while (c.moveToNext()) out.add(new Task(c));
        }
        return out;
    }
    public synchronized void setEnabled(String id, boolean enabled) { ContentValues v = new ContentValues(); v.put("enabled", enabled ? 1 : 0); v.put("updated_at", java.time.Instant.now().toString()); getWritableDatabase().update("tasks", v, "id=?", new String[]{id}); }
    public synchronized void delete(String id) { getWritableDatabase().delete("tasks", "id=?", new String[]{id}); }
    public synchronized void record(String taskId, long runId, String status, String detail) {
        ContentValues v = new ContentValues(); v.put("task_id", taskId); v.put("run_id", runId); v.put("status", status); v.put("detail", detail); v.put("created_at", java.time.Instant.now().toString()); getWritableDatabase().insert("runs", null, v);
        getWritableDatabase().execSQL("DELETE FROM runs WHERE id NOT IN (SELECT id FROM runs ORDER BY id DESC LIMIT 500)");
    }
    public synchronized void updateCheck(Task t, String localDate, long lastRunId, boolean baseline, String status, String checkedAt) {
        ContentValues v = new ContentValues(); v.put("last_local_date", localDate); v.put("last_seen_run_id", lastRunId); v.put("baseline", baseline ? 1 : 0); v.put("last_status", status); v.put("updated_at", java.time.Instant.now().toString()); v.put("last_checked_at", checkedAt); getWritableDatabase().update("tasks", v, "id=?", new String[]{t.id});
    }
    public synchronized List<Activity> recentRuns(int limit) {
        List<Activity> out = new ArrayList<>();
        try (Cursor c = getReadableDatabase().rawQuery(
                "SELECT COALESCE(tasks.name,runs.task_id),runs.status,runs.detail,runs.created_at " +
                "FROM runs LEFT JOIN tasks ON tasks.id=runs.task_id ORDER BY runs.id DESC LIMIT ?",
                new String[]{String.valueOf(limit)})) {
            while (c.moveToNext()) out.add(new Activity(c.getString(0), c.getString(1), c.getString(2), c.getString(3)));
        }
        return out;
    }
    public static final class Activity {
        public final String taskName, status, detail, createdAt;
        Activity(String taskName, String status, String detail, String createdAt) {
            this.taskName=taskName; this.status=status; this.detail=detail; this.createdAt=createdAt;
        }
    }
    public static final class Task {
        public final String id,name,repo,branch,workflow,zone,lastDate,lastStatus,lastCheckedAt,days; public final int hour,minute; public final boolean enabled,baseline; public final long lastRunId;
        Task(Cursor c) { id=c.getString(0); name=c.getString(1); repo=c.getString(2); branch=c.getString(3); workflow=c.getString(4); hour=c.getInt(5); minute=c.getInt(6); zone=c.getString(7); enabled=c.getInt(8)==1; lastDate=c.getString(9); lastRunId=c.getLong(10); baseline=c.getInt(11)==1; lastStatus=c.getString(12); lastCheckedAt=c.isNull(13)?null:c.getString(13); days=c.getString(14); }
    }
}
