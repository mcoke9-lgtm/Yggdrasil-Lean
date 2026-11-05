package org.yggdrasil.scheduler.builder;

import android.content.Context;
import android.content.SharedPreferences;
import org.json.JSONException;
import org.json.JSONObject;

/** Small durable checkpoint for scheduled overnight runs; conversation turns live in the canonical journal. */
public final class BuilderOvernightRunStore {
    public static final long MAX_DURATION_MILLIS = 8L * 60L * 60L * 1000L;
    private final SharedPreferences preferences;

    public BuilderOvernightRunStore(Context context) {
        if (context == null) throw new IllegalArgumentException("Context is required");
        preferences = context.getApplicationContext().getSharedPreferences(
                "builder_overnight_runs", Context.MODE_PRIVATE);
    }

    public synchronized JSONObject beginOrRecover(String runId, String threadId,
            String taskPrompt, long nowMillis) {
        requireText(runId, "runId");
        requireText(threadId, "threadId");
        requireText(taskPrompt, "taskPrompt");
        JSONObject state = read(runId);
        if (state == null) {
            state = new JSONObject();
            try {
                state.put("run_id", runId);
                state.put("thread_id", threadId);
                state.put("task_prompt_sha256", BuilderDisclosureFingerprint.digest(taskPrompt));
                state.put("status", "RUNNING");
                state.put("started_at_epoch_millis", nowMillis);
                state.put("deadline_epoch_millis", nowMillis + MAX_DURATION_MILLIS);
                state.put("completed_cycles", 0);
                state.put("cycle_in_progress", false);
                state.put("updated_at_epoch_millis", nowMillis);
            } catch (JSONException impossible) {
                throw new IllegalStateException(impossible);
            }
            write(runId, state);
            return state;
        }
        if (!threadId.equals(state.optString("thread_id"))
                || !BuilderDisclosureFingerprint.digest(taskPrompt).equals(
                        state.optString("task_prompt_sha256"))) {
            throw new IllegalStateException("Run identity, thread, or approved task prompt changed");
        }
        if (state.optBoolean("cycle_in_progress", false)) {
            state = status(state, "INTERRUPTED", nowMillis,
                    "Host stopped during a Builder cycle; partial work requires review and will not be replayed.");
            write(runId, state);
            return state;
        }
        if ("RUNNING".equals(state.optString("status"))
                && nowMillis >= state.optLong("deadline_epoch_millis")) {
            state = status(state, "EXPIRED", nowMillis, "Eight-hour execution window elapsed.");
            write(runId, state);
        }
        return state;
    }

    public synchronized JSONObject beginCycle(String runId, long nowMillis) {
        JSONObject state = requireRun(runId);
        if (!"RUNNING".equals(state.optString("status")))
            throw new IllegalStateException("Only an active overnight run can start a cycle");
        if (nowMillis >= state.optLong("deadline_epoch_millis"))
            throw new IllegalStateException("Eight-hour execution window has expired");
        if (state.optBoolean("cycle_in_progress", false))
            throw new IllegalStateException("A prior cycle is still marked in progress");
        try {
            state.put("cycle_in_progress", true);
            state.put("current_cycle", state.optInt("completed_cycles") + 1);
            state.put("updated_at_epoch_millis", nowMillis);
        } catch (JSONException impossible) { throw new IllegalStateException(impossible); }
        write(runId, state);
        return state;
    }

    public synchronized JSONObject finishCycle(String runId, String summary, long nowMillis) {
        JSONObject state = requireRun(runId);
        if (!"RUNNING".equals(state.optString("status")))
            throw new IllegalStateException("A cancelled or terminal run cannot complete another cycle");
        if (!state.optBoolean("cycle_in_progress", false))
            throw new IllegalStateException("No Builder cycle is marked in progress");
        try {
            state.put("completed_cycles", state.optInt("current_cycle"));
            state.put("cycle_in_progress", false);
            state.put("last_cycle_summary", summary == null ? "" : summary.substring(0, Math.min(2000, summary.length())));
            state.put("last_checkpoint_epoch_millis", nowMillis);
            state.put("updated_at_epoch_millis", nowMillis);
        } catch (JSONException impossible) { throw new IllegalStateException(impossible); }
        write(runId, state);
        return state;
    }

    public synchronized JSONObject setStatus(String runId, String status, String reason, long nowMillis) {
        JSONObject state = requireRun(runId);
        String current = state.optString("status");
        if (!"RUNNING".equals(current) && !current.equals(status))
            throw new IllegalStateException("Terminal run state " + current + " cannot be overwritten by " + status);
        state = status(state, status, nowMillis, reason);
        write(runId, state);
        return state;
    }

    public synchronized JSONObject read(String runId) {
        String raw = preferences.getString(key(runId), null);
        if (raw == null) return null;
        try { return new JSONObject(raw); }
        catch (JSONException corrupt) {
            throw new IllegalStateException("Overnight run checkpoint is malformed; refusing automatic replay", corrupt);
        }
    }

    private JSONObject requireRun(String runId) {
        JSONObject state = read(runId);
        if (state == null) throw new IllegalStateException("Overnight run checkpoint does not exist");
        return state;
    }

    private void write(String runId, JSONObject state) {
        if (!preferences.edit().putString(key(runId), state.toString()).commit())
            throw new IllegalStateException("Could not persist overnight run checkpoint");
    }

    private static JSONObject status(JSONObject state, String status, long nowMillis, String reason) {
        try {
            state.put("status", status);
            state.put("cycle_in_progress", false);
            state.put("updated_at_epoch_millis", nowMillis);
            state.put("status_reason", reason == null ? "" : reason);
            return state;
        } catch (JSONException impossible) { throw new IllegalStateException(impossible); }
    }

    private static String key(String runId) { return "run." + requireText(runId, "runId"); }
    private static String requireText(String value, String label) {
        if (value == null || value.trim().isEmpty()) throw new IllegalArgumentException(label + " is required");
        return value.trim();
    }
}
