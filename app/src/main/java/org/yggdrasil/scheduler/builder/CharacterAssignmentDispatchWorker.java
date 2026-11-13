package org.yggdrasil.scheduler.builder;

import android.content.Context;
import androidx.annotation.NonNull;
import androidx.work.Worker;
import androidx.work.WorkerParameters;
import org.json.JSONObject;

/**
 * Claims at most one authorized assignment.
 *
 * This first integration increment intentionally stops at the provider boundary:
 * no model/provider adapter is invoked and no work is represented as RUNNING.
 */
public final class CharacterAssignmentDispatchWorker extends Worker {
    public CharacterAssignmentDispatchWorker(@NonNull Context context,
            @NonNull WorkerParameters params) {
        super(context, params);
    }

    @NonNull @Override public Result doWork() {
        CharacterAssignmentStore store =
                new CharacterAssignmentStore(getApplicationContext());
        CharacterAssignmentStore.Assignment assignment = null;
        try {
            assignment = store.claimNextQueued();
            if (assignment == null) return Result.success();

            JSONObject report = new JSONObject();
            report.put("assignment_id", assignment.assignmentId);
            report.put("character", assignment.character);
            report.put("outcome", "BLOCKED");
            report.put("reason_code", "NO_EXECUTION_ADAPTER");
            report.put("observed", "WorkManager claimed the authorized assignment.");
            report.put("execution_started", false);
            report.put("authorization_status", "AUTHORIZED_BEFORE_QUEUE");
            report.put("next_action", "Connect an approved provider adapter before execution.");

            store.returnReport(assignment.assignmentId, report.toString());
            store.transition(assignment.assignmentId,
                    CharacterAssignmentStore.Status.BLOCKED,
                    "Stopped safely: no execution adapter is connected");
            return Result.success();
        } catch (Exception failure) {
            // Preserve a recoverable state instead of leaving a claimed assignment silently ACCEPTED.
            if (assignment != null) {
                try {
                    String detail = failure.getMessage() == null
                            ? failure.getClass().getSimpleName() : failure.getMessage();
                    if (detail.length() > 240) detail = detail.substring(0, 240);
                    store.transition(assignment.assignmentId,
                            CharacterAssignmentStore.Status.INTERRUPTED,
                            "Dispatcher interrupted: " + detail);
                } catch (Exception ignored) {
                    // The durable ACCEPTED state remains visible for explicit recovery.
                }
            }
            // Do not retry indefinitely or bypass the authorization boundary.
            return Result.failure();
        } finally {
            store.close();
        }
    }
}
