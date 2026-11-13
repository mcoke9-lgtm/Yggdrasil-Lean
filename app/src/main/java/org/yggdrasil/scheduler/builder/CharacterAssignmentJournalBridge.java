package org.yggdrasil.scheduler.builder;

import org.json.JSONException;
import org.json.JSONObject;
import java.util.List;
import java.util.UUID;

/**
 * Projects assignment lifecycle evidence into the append-only Character conversation journal.
 * Journal entries preserve assignment provenance but do not authorize work or prove execution.
 */
public final class CharacterAssignmentJournalBridge {
    private static final String THREAD_PREFIX = "mdm-assignment:";
    private static final String REQUEST_SUFFIX = ":request";
    private static final String ACCEPTED_SUFFIX = ":accepted";
    private static final String RETURN_SUFFIX = ":return";

    private CharacterAssignmentJournalBridge() {}

    public static String threadId(String assignmentId) {
        requireText(assignmentId, "assignmentId");
        return THREAD_PREFIX + assignmentId;
    }

    /** Append an idempotent request and Character acknowledgement after the ledger claim. */
    public static void appendAccepted(
            CharacterAssignmentStore.Assignment assignment,
            BuilderConversationJournal journal) {
        requireInputs(assignment, journal);
        String threadId = threadId(assignment.assignmentId);
        String requestId = stableTurnId(assignment.assignmentId, REQUEST_SUFFIX);
        String acceptedId = stableTurnId(assignment.assignmentId, ACCEPTED_SUFFIX);
        BuilderConversationProtocol.Participant requester = requesterParticipant(assignment.requester);
        List<BuilderConversationProtocol.Turn> turns = journal.readThread(threadId);
        if (!containsTurn(turns, requestId)) {
            JSONObject request = envelope(assignment, "ASSIGNMENT_REQUEST");
            append(journal, threadId, requestId, requester, null, null,
                    BuilderConversationProtocol.TurnType.CONTRIBUTION,
                    requester == BuilderConversationProtocol.Participant.USER
                            ? BuilderConversationProtocol.EvidenceStatus.USER_CONFIRMED
                            : BuilderConversationProtocol.EvidenceStatus.OBSERVED,
                    request, assignment.assignmentId);
        }
        if (containsTurn(journal.readThread(threadId), acceptedId)) return;
        JSONObject body = envelope(assignment, "ACCEPTED");
        try { body.put("execution_started", false); }
        catch (JSONException impossible) { throw new IllegalStateException(impossible); }
        append(journal, threadId, acceptedId, participant(assignment.character),
                requester, requestId,
                BuilderConversationProtocol.TurnType.ACKNOWLEDGEMENT,
                BuilderConversationProtocol.EvidenceStatus.OBSERVED, body, assignment.assignmentId);
    }

    /** Append the structured return report as a Character response to the assignment request. */
    public static void appendReturnReport(
            CharacterAssignmentStore.Assignment assignment,
            String reportJson,
            BuilderConversationJournal journal) {
        requireInputs(assignment, journal);
        requireText(reportJson, "reportJson");
        String threadId = threadId(assignment.assignmentId);
        String requestId = stableTurnId(assignment.assignmentId, REQUEST_SUFFIX);
        String returnId = stableTurnId(assignment.assignmentId, RETURN_SUFFIX);
        List<BuilderConversationProtocol.Turn> turns = journal.readThread(threadId);
        if (containsTurn(turns, returnId)) return;
        if (!containsTurn(turns, stableTurnId(assignment.assignmentId, ACCEPTED_SUFFIX))) {
            appendAccepted(assignment, journal);
        }

        JSONObject body = envelope(assignment, "RETURN_REPORT");
        try {
            body.put("report", new JSONObject(reportJson));
        } catch (JSONException notObject) {
            throw new IllegalArgumentException("return report must be a JSON object", notObject);
        }
        append(journal, threadId, returnId, participant(assignment.character),
                requesterParticipant(assignment.requester), requestId,
                BuilderConversationProtocol.TurnType.RESPONSE,
                BuilderConversationProtocol.EvidenceStatus.OBSERVED, body, assignment.assignmentId);
    }

    private static JSONObject envelope(CharacterAssignmentStore.Assignment assignment, String event) {
        JSONObject body = new JSONObject();
        put(body, "assignment_id", assignment.assignmentId);
        put(body, "character", assignment.character);
        put(body, "requester", assignment.requester);
        put(body, "event", event);
        return body;
    }

    private static void append(BuilderConversationJournal journal, String threadId, String turnId,
            BuilderConversationProtocol.Participant speaker,
            BuilderConversationProtocol.Participant recipient, String replyTo,
            BuilderConversationProtocol.TurnType type,
            BuilderConversationProtocol.EvidenceStatus evidence, JSONObject body,
            String assignmentId) {
        List<BuilderConversationProtocol.Turn> prior = journal.readThread(threadId);
        long last = prior.isEmpty() ? 0L : prior.get(prior.size() - 1).timestampEpochMillis;
        BuilderConversationProtocol.Turn turn = new BuilderConversationProtocol.Turn(
                BuilderConversationProtocol.CURRENT_VERSION, threadId, turnId, speaker,
                recipient, replyTo, type, evidence,
                Math.max(System.currentTimeMillis(), last + 1L), body.toString(), assignmentId);
        journal.append(turn);
    }

    private static boolean containsTurn(List<BuilderConversationProtocol.Turn> turns, String turnId) {
        for (BuilderConversationProtocol.Turn turn : turns) {
            if (turnId.equals(turn.turnId)) return true;
        }
        return false;
    }

    private static String stableTurnId(String assignmentId, String suffix) {
        return UUID.nameUUIDFromBytes((THREAD_PREFIX + assignmentId + suffix)
                .getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString();
    }

    private static BuilderConversationProtocol.Participant participant(String character) {
        try {
            return BuilderConversationProtocol.Participant.valueOf(character);
        } catch (IllegalArgumentException invalid) {
            throw new IllegalArgumentException("Character has no conversation participant: " + character, invalid);
        }
    }

    /** Preserve a named Nine Character requester; otherwise represent the human director as USER. */
    private static BuilderConversationProtocol.Participant requesterParticipant(String requester) {
        try {
            return BuilderConversationProtocol.Participant.valueOf(
                    requester.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException invalid) {
            return BuilderConversationProtocol.Participant.USER;
        }
    }

    private static void requireInputs(CharacterAssignmentStore.Assignment assignment,
            BuilderConversationJournal journal) {
        if (assignment == null) throw new IllegalArgumentException("assignment is required");
        if (journal == null) throw new IllegalArgumentException("journal is required");
    }

    private static void requireText(String value, String field) {
        if (value == null || value.trim().isEmpty())
            throw new IllegalArgumentException(field + " must not be blank");
    }

    private static void put(JSONObject object, String key, String value) {
        try { object.put(key, value); }
        catch (JSONException impossible) { throw new IllegalStateException(impossible); }
    }
}
