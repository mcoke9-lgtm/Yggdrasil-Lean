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
            return new BuilderConversationCoordinator.Reply(
                    body, evidence, type, recipient, replyTo);
        } catch (JSONException malformed) {
            throw new IllegalArgumentException("Builder response is not valid JSON", malformed);
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
