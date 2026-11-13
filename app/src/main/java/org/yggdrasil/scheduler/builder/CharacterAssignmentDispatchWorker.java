package org.yggdrasil.scheduler.builder;

import android.content.Context;
import androidx.annotation.NonNull;
import androidx.work.Worker;
import androidx.work.WorkerParameters;
import org.json.JSONObject;
import java.util.UUID;

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
        BuilderConversationStore journal = new BuilderConversationStore(getApplicationContext());
        try {
            assignment = store.claimNextQueued();
            if (assignment == null) return Result.success();
            appendJournalTurn(journal, assignment, "WorkManager accepted the authorized assignment; execution has not started.");

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
            appendJournalTurn(journal, assignment, report.toString());
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
            journal.close();
            store.close();
        }
    }

    private static void appendJournalTurn(BuilderConversationStore journal,
            CharacterAssignmentStore.Assignment assignment, String body) {
        BuilderConversationProtocol.Turn turn = new BuilderConversationProtocol.Turn(
                BuilderConversationProtocol.CURRENT_VERSION,
                "assignment-" + assignment.assignmentId, UUID.randomUUID().toString(),
                BuilderConversationProtocol.Participant.valueOf(assignment.character),
                null, null, BuilderConversationProtocol.TurnType.CONTRIBUTION,
                BuilderConversationProtocol.EvidenceStatus.OBSERVED,
                System.currentTimeMillis(), body, assignment.assignmentId);
        journal.append(turn);
    }
}
