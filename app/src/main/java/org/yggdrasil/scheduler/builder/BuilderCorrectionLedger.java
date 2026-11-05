package org.yggdrasil.scheduler.builder;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.List;
import java.util.UUID;

/**
 * Append-only correction lifecycle validation and explicit user adoption.
 * Adoption records acceptance of a correction as project practice; it never executes or deploys it.
 */
public final class BuilderCorrectionLedger {
    private static final String[] PLAN_FIELDS = {
            "correction_id", "incident_turn_id", "objective", "acceptance_criteria",
            "steps", "owner", "rollback_condition", "status"
    };
    private static final String[] UPDATE_FIELDS = {
            "correction_id", "incident_turn_id", "stage", "evidence",
            "artifact_reference", "acceptance_criteria_result", "unresolved"
    };
    private static final String[] STAGES = {"PROPOSED", "IMPLEMENTED", "TESTED", "VERIFIED", "ADOPTED"};

    private BuilderCorrectionLedger() {}

    public static void validatePlan(BuilderConversationProtocol.Turn plan,
                                    List<BuilderConversationProtocol.Turn> context,
                                    String requiredIncidentId) {
        if (plan.type != BuilderConversationProtocol.TurnType.CORRECTION_PLAN)
            throw new IllegalArgumentException("Expected CORRECTION_PLAN");
        if (plan.speaker == BuilderConversationProtocol.Participant.USER)
            throw new IllegalArgumentException("Only a Builder may propose a correction plan");
        if (plan.evidenceStatus != BuilderConversationProtocol.EvidenceStatus.PROPOSED)
            throw new IllegalArgumentException("A correction plan must begin as PROPOSED");
        if (requiredIncidentId == null || !requiredIncidentId.equals(plan.replyToTurnId))
            throw new IllegalArgumentException("Correction plan must reference the reviewed incident");
        BuilderConversationProtocol.Turn incident = findTurn(context, requiredIncidentId);
        if (incident == null || incident.type != BuilderConversationProtocol.TurnType.IMPROVEMENT_INCIDENT)
            throw new IllegalArgumentException("Correction plan must reference a recorded improvement incident");
        JSONObject body = object(plan.body, "Correction plan");
        for (String field : PLAN_FIELDS) required(body, field);
        if (!"PROPOSED".equals(required(body, "status")))
            throw new IllegalArgumentException("Correction plan status must be PROPOSED");
        String correctionId = required(body, "correction_id");
        if (findPlan(context, correctionId) != null)
            throw new IllegalArgumentException("Correction ID already exists: " + correctionId);
        if (!requiredIncidentId.equals(required(body, "incident_turn_id")))
            throw new IllegalArgumentException("Correction plan incident_turn_id does not match its reference");
    }

    public static void validateUpdate(BuilderConversationProtocol.Turn update,
                                      List<BuilderConversationProtocol.Turn> context) {
        if (update.type != BuilderConversationProtocol.TurnType.CORRECTION_UPDATE)
            throw new IllegalArgumentException("Expected CORRECTION_UPDATE");
        if (update.speaker == BuilderConversationProtocol.Participant.USER)
            throw new IllegalArgumentException("Builder update validation does not accept user adoption");
        JSONObject body = object(update.body, "Correction update");
        for (String field : UPDATE_FIELDS) required(body, field);
        String correctionId = required(body, "correction_id");
        String incidentId = required(body, "incident_turn_id");
        String stage = required(body, "stage");
        String evidence = required(body, "evidence");
        String artifact = required(body, "artifact_reference");
        String criteria = required(body, "acceptance_criteria_result");
        required(body, "unresolved");
        if ("ADOPTED".equals(stage) || "PROPOSED".equals(stage))
            throw new IllegalArgumentException("Builders cannot create ADOPTED or repeat PROPOSED updates");
        if (update.evidenceStatus.name().equals(stage) == false)
            throw new IllegalArgumentException("Turn evidence_status must match correction stage");
        int stageIndex = stageIndex(stage);
        if (stageIndex < 1 || stageIndex > 3)
            throw new IllegalArgumentException("Builder correction stage must be IMPLEMENTED, TESTED, or VERIFIED");
        BuilderConversationProtocol.Turn plan = findPlan(context, correctionId);
        if (plan == null)
            throw new IllegalArgumentException("Correction update references no recorded plan");
        JSONObject planBody = object(plan.body, "Correction plan");
        if (!incidentId.equals(required(planBody, "incident_turn_id")))
            throw new IllegalArgumentException("Correction update incident does not match its plan");
        BuilderConversationProtocol.Turn incident = findTurn(context, incidentId);
        if (incident == null || incident.type != BuilderConversationProtocol.TurnType.IMPROVEMENT_INCIDENT)
            throw new IllegalArgumentException("Correction update references no recorded incident");

        BuilderConversationProtocol.Turn latest = plan;
        String latestStage = "PROPOSED";
        for (BuilderConversationProtocol.Turn prior : context) {
            if (prior.type != BuilderConversationProtocol.TurnType.CORRECTION_UPDATE) continue;
            JSONObject priorBody = object(prior.body, "Prior correction update");
            if (correctionId.equals(required(priorBody, "correction_id"))) {
                latest = prior;
                latestStage = required(priorBody, "stage");
            }
        }
        if (!latest.turnId.equals(update.replyToTurnId))
            throw new IllegalArgumentException("Correction update must reference the immediately preceding lifecycle record");
        if (stageIndex(stage) != stageIndex(latestStage) + 1)
            throw new IllegalArgumentException("Correction stages must advance one step at a time from "
                    + latestStage + "; received " + stage);
        if (stageIndex == 3) {
            if (update.speaker != BuilderConversationProtocol.Participant.BALDR)
                throw new IllegalArgumentException("Only Baldr may independently verify a correction");
            if (incident.speaker == BuilderConversationProtocol.Participant.BALDR)
                throw new IllegalArgumentException("Baldr cannot verify a correction for an incident Baldr authored");
            if (artifact.toLowerCase(java.util.Locale.ROOT).contains("self-review"))
                throw new IllegalArgumentException("Verification artifact cannot be a self-review");
        }
        if (evidence.length() < 8 || artifact.length() < 3 || criteria.length() < 8)
            throw new IllegalArgumentException("Correction stage requires substantive evidence, artifact, and acceptance-criteria result");
    }

    /**
     * Append an explicit user adoption record after a VERIFIED correction.
     * This records acceptance only; it performs no deployment, publication, or external action.
     */
    public static BuilderConversationProtocol.Turn recordUserAdoption(
            BuilderConversationJournal journal, String threadId, String correctionId) {
        if (journal == null) throw new IllegalArgumentException("Correction journal is required");
        if (threadId == null || threadId.trim().isEmpty())
            throw new IllegalArgumentException("Thread ID is required");
        if (correctionId == null || correctionId.trim().isEmpty())
            throw new IllegalArgumentException("Correction ID is required");
        List<BuilderConversationProtocol.Turn> context = journal.readThread(threadId);
        BuilderConversationProtocol.Turn plan = findPlan(context, correctionId.trim());
        if (plan == null) throw new IllegalArgumentException("Correction plan was not found");
        BuilderConversationProtocol.Turn latest = plan;
        String latestStage = "PROPOSED";
        for (BuilderConversationProtocol.Turn prior : context) {
            if (prior.type != BuilderConversationProtocol.TurnType.CORRECTION_UPDATE) continue;
            JSONObject body = object(prior.body, "Correction update");
            if (correctionId.trim().equals(required(body, "correction_id"))) {
                latest = prior;
                latestStage = required(body, "stage");
            }
        }
        if (!"VERIFIED".equals(latestStage))
            throw new IllegalStateException("Only the latest VERIFIED correction can be adopted");
        JSONObject verifiedBody = object(latest.body, "Verified correction update");
        JSONObject adoption = new JSONObject();
        try {
            adoption.put("correction_id", correctionId.trim());
            adoption.put("incident_turn_id", required(verifiedBody, "incident_turn_id"));
            adoption.put("stage", "ADOPTED");
            adoption.put("evidence", "The user explicitly accepted the verified correction record.");
            adoption.put("artifact_reference", "user-adoption:" + correctionId.trim());
            adoption.put("acceptance_criteria_result", "User reviewed the recorded VERIFIED milestone and accepted the correction as project practice.");
            adoption.put("unresolved", required(verifiedBody, "unresolved"));
        } catch (JSONException impossible) {
            throw new IllegalStateException("Could not create user adoption record", impossible);
        }
        long timestamp = Math.max(System.currentTimeMillis(),
                context.get(context.size() - 1).timestampEpochMillis + 1L);
        BuilderConversationProtocol.Turn adopted = new BuilderConversationProtocol.Turn(
                BuilderConversationProtocol.CURRENT_VERSION, threadId.trim(), UUID.randomUUID().toString(),
                BuilderConversationProtocol.Participant.USER, null, latest.turnId,
                BuilderConversationProtocol.TurnType.CORRECTION_UPDATE,
                BuilderConversationProtocol.EvidenceStatus.ADOPTED, timestamp, adoption.toString());
        journal.append(adopted);
        return adopted;
    }

    /** Returns the most recent correction ID that is verified and not yet adopted. */
    public static String latestAdoptableCorrection(List<BuilderConversationProtocol.Turn> context) {
        String candidate = null;
        long candidateTime = 0L;
        for (BuilderConversationProtocol.Turn turn : context) {
            if (turn.type != BuilderConversationProtocol.TurnType.CORRECTION_UPDATE
                    || turn.evidenceStatus != BuilderConversationProtocol.EvidenceStatus.VERIFIED) continue;
            JSONObject body = object(turn.body, "Correction update");
            String id = required(body, "correction_id");
            boolean adopted = false;
            for (BuilderConversationProtocol.Turn later : context) {
                if (later.timestampEpochMillis <= turn.timestampEpochMillis
                        || later.type != BuilderConversationProtocol.TurnType.CORRECTION_UPDATE) continue;
                JSONObject laterBody = object(later.body, "Correction update");
                if (id.equals(required(laterBody, "correction_id"))
                        && "ADOPTED".equals(required(laterBody, "stage"))) {
                    adopted = true;
                    break;
                }
            }
            if (!adopted && turn.timestampEpochMillis > candidateTime) {
                candidate = id;
                candidateTime = turn.timestampEpochMillis;
            }
        }
        return candidate;
    }

    private static BuilderConversationProtocol.Turn findPlan(
            List<BuilderConversationProtocol.Turn> context, String correctionId) {
        for (BuilderConversationProtocol.Turn turn : context) {
            if (turn.type != BuilderConversationProtocol.TurnType.CORRECTION_PLAN) continue;
            JSONObject body = object(turn.body, "Correction plan");
            if (correctionId.equals(required(body, "correction_id"))) return turn;
        }
        return null;
    }

    private static BuilderConversationProtocol.Turn findTurn(
            List<BuilderConversationProtocol.Turn> context, String turnId) {
        for (BuilderConversationProtocol.Turn turn : context)
            if (turn.turnId.equals(turnId)) return turn;
        return null;
    }

    private static int stageIndex(String stage) {
        for (int i = 0; i < STAGES.length; i++) if (STAGES[i].equals(stage)) return i;
        return -1;
    }

    private static JSONObject object(String json, String label) {
        try {
            return new JSONObject(json);
        } catch (JSONException malformed) {
            throw new IllegalArgumentException(label + " must be a JSON object", malformed);
        }
    }

    private static String required(JSONObject object, String field) {
        if (!object.has(field) || object.isNull(field))
            throw new IllegalArgumentException("Missing " + field);
        Object value;
        try {
            value = object.get(field);
        } catch (JSONException malformed) {
            throw new IllegalArgumentException("Invalid " + field, malformed);
        }
        if (!(value instanceof String) || ((String) value).trim().isEmpty())
            throw new IllegalArgumentException(field + " must be a non-empty string");
        return ((String) value).trim();
    }
}
