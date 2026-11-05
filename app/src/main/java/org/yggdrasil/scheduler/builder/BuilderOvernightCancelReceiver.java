package org.yggdrasil.scheduler.builder;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import androidx.work.WorkManager;

/** Explicit notification action to cancel the unique overnight work chain. */
public final class BuilderOvernightCancelReceiver extends BroadcastReceiver {
    public static final String ACTION_CANCEL = "org.yggdrasil.scheduler.CANCEL_OVERNIGHT_RUN";
    public static final String EXTRA_RUN_ID = "run_id";

    @Override public void onReceive(Context context, Intent intent) {
        if (intent == null || !ACTION_CANCEL.equals(intent.getAction())) return;
        String runId = intent.getStringExtra(EXTRA_RUN_ID);
        if (runId == null || runId.trim().isEmpty()) return;
        BuilderOvernightRunStore store = new BuilderOvernightRunStore(context);
        org.json.JSONObject state = store.read(runId);
        if (state != null && "RUNNING".equals(state.optString("status"))) {
            store.setStatus(runId, "CANCELLED",
                    "Stopped from the persistent run notification.", System.currentTimeMillis());
        }
        WorkManager.getInstance(context.getApplicationContext())
                .cancelUniqueWork(BuilderOvernightScheduler.workName(runId));
    }
}