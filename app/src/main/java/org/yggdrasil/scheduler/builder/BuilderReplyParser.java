package org.yggdrasil.scheduler.builder;

import org.json.JSONException;
import org.json.JSONObject;

/** Strict parser for the provider-neutral JSON response contract. */
public final class BuilderReplyParser {
    private BuilderReplyParser() {}

    public static BuilderConversationCoordinator.Reply parse(String json) {
        if (json == null || json.trim().isEmpty())
            throw new IllegalArgumentException("Builder response JSON must not be blank");
        try {
            JSONObject object = new JSONObject(json);
            String body = requiredString(object, "body");
            String typeName = requiredString(object, "turn_type");
            String evidenceName = requiredString(object, "evidence_status");

            BuilderConversationProtocol.TurnType type;
            BuilderConversationProtocol.EvidenceStatus evidence;
            try {
                type = BuilderConversationProtocol.TurnType.valueOf(typeName);
                evidence = BuilderConversationProtocol.EvidenceStatus.valueOf(evidenceName);
            } catch (IllegalArgumentException invalidEnum) {
                throw new IllegalArgumentException("Builder response contains an unsupported turn or evidence status",
                        invalidEnum);
            }

            BuilderConversationProtocol.Participant recipient = null;
            if (object.has("recipient") && !object.isNull("recipient")) {
                String recipientName = requiredString(object, "recipient");
                try {
                    recipient = BuilderConversationProtocol.Participant.valueOf(recipientName);
                } catch (IllegalArgumentException invalidRecipient) {
                    throw new IllegalArgumentException("Builder response names an unknown recipient",
                            invalidRecipient);
                }
            }

            String replyTo = null;
            if (object.has("reply_to_turn_id") && !object.isNull("reply_to_turn_id")) {
                replyTo = requiredString(object, "reply_to_turn_id");
            }
            if (evidence == BuilderConversationProtocol.EvidenceStatus.ADOPTED)
                throw new IllegalArgumentException("Builders cannot emit ADOPTED evidence status");
            boolean stopEvent = type == BuilderConversationProtocol.TurnType.INTERRUPTION
                    || type == BuilderConversationProtocol.TurnType.IMPROVEMENT_INCIDENT;
            boolean correctionEvent = type == BuilderConversationProtocol.TurnType.CORRECTION_PLAN
                    || type == BuilderConversationProtocol.TurnType.CORRECTION_UPDATE;
            if ((stopEvent || correctionEvent) && recipient != BuilderConversationProtocol.Participant.USER)
                throw new IllegalArgumentException(type + " must be addressed to USER");
            if ((stopEvent || correctionEvent) && replyTo == null)
                throw new IllegalArgumentException(type + " must reference an earlier turn");
            if (type == BuilderConversationProtocol.TurnType.IMPROVEMENT_INCIDENT) {
                validateIncidentBody(body);
            } else if (type == BuilderConversationProtocol.TurnType.INTERRUPTION) {
                validateInterruptionBody(body);
            } else if (type == BuilderConversationProtocol.TurnType.CORRECTION_PLAN) {
                validateCorrectionPlanBody(body);
                if (evidence != BuilderConversationProtocol.EvidenceStatus.PROPOSED)
                    throw new IllegalArgumentException("Correction plan evidence_status must be PROPOSED");
            } else if (type == BuilderConversationProtocol.TurnType.CORRECTION_UPDATE) {
                validateCorrectionUpdateBody(body);
                JSONObject update = new JSONObject(body);
                String stage = requiredString(update, "stage");
                if ("ADOPTED".equals(stage) || "PROPOSED".equals(stage))
                    throw new IllegalArgumentException("Builders cannot create ADOPTED or repeat PROPOSED updates");
                if (!evidence.name().equals(stage))
                    throw new IllegalArgumentException("Correction update evidence_status must match stage");
            }
            return new BuilderConversationCoordinator.Reply(
                    body, evidence, type, recipient, replyTo);
        } catch (JSONException malformed) {
            throw new IllegalArgumentException("Builder response is not valid JSON", malformed);
        }
    }

    private static void validateInterruptionBody(String body) {
        try {
            JSONObject interruption = new JSONObject(body);
            String[] required = {"stop_state", "observed_constraint", "source_time",
                    "affected_step", "impact", "unknowns", "feasible_alternative",
                    "required_recheck_authorization", "resume_condition"};
            for (String field : required) requiredString(interruption, field);
            String stopState = requiredString(interruption, "stop_state");
            if (!"STOP".equals(stopState) && !"PAUSE".equals(stopState))
                throw new IllegalArgumentException("Interruption stop_state must be STOP or PAUSE");
        } catch (JSONException malformed) {
            throw new IllegalArgumentException("Interruption body must be a JSON object", malformed);
        }
    }

    private static void validateIncidentBody(String body) {
        try {
            JSONObject incident = new JSONObject(body);
            String[] required = {"incident_summary", "observed_behavior", "expected_behavior",
                    "evidence", "unknowns", "suspected_causes", "proposed_next_step", "status"};
            for (String field : required) requiredString(incident, field);
            String status = requiredString(incident, "status");
            if (!"OPEN_DIAGNOSIS".equals(status) && !"OPEN_REVIEW".equals(status))
                throw new IllegalArgumentException("Improvement incident status must remain OPEN_DIAGNOSIS or OPEN_REVIEW");
        } catch (JSONException malformed) {
            throw new IllegalArgumentException("Improvement incident body must be a JSON object", malformed);
        }
    }

    private static void validateCorrectionPlanBody(String body) {
        try {
            JSONObject plan = new JSONObject(body);
            String[] required = {"correction_id", "incident_turn_id", "objective",
                    "acceptance_criteria", "steps", "owner", "rollback_condition", "status"};
            for (String field : required) requiredString(plan, field);
            if (!"PROPOSED".equals(requiredString(plan, "status")))
                throw new IllegalArgumentException("Correction plan status must be PROPOSED");
        } catch (JSONException malformed) {
            throw new IllegalArgumentException("Correction plan body must be a JSON object", malformed);
        }
    }

    private static void validateCorrectionUpdateBody(String body) {
        try {
            JSONObject update = new JSONObject(body);
            String[] required = {"correction_id", "incident_turn_id", "stage", "evidence",
                    "artifact_reference", "acceptance_criteria_result", "unresolved"};
            for (String field : required) requiredString(update, field);
            String stage = requiredString(update, "stage");
            if (!"IMPLEMENTED".equals(stage) && !"TESTED".equals(stage)
                    && !"VERIFIED".equals(stage))
                throw new IllegalArgumentException("Builder correction update stage must be IMPLEMENTED, TESTED, or VERIFIED");
        } catch (JSONException malformed) {
            throw new IllegalArgumentException("Correction update body must be a JSON object", malformed);
        }
    }

    private static String requiredString(JSONObject object, String field) throws JSONException {
        if (!object.has(field) || object.isNull(field))
            throw new IllegalArgumentException("Builder response is missing " + field);
        Object value = object.get(field);
        if (!(value instanceof String) || ((String) value).trim().isEmpty())
            throw new IllegalArgumentException("Builder response field " + field + " must be a non-empty string");
        return ((String) value).trim();
    }
}
