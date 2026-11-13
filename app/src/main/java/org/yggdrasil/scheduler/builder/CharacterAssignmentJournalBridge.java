package org.yggdrasil.scheduler.builder;

import org.json.JSONException;
import org.json.JSONObject;
import java.util.List;
import java.util.UUID;

/**
 * Projects assignment lifecycle evidence into the append-only Character conversation journal.
 *
 * The journal thread is namespaced by assignment ID. Journal entries are a durable
 * communication record; they do not authorize work or prove that objective execution occurred.
 */
public final class CharacterAssignmentJournalBridge {
    private static final String THREAD_PREFIX = "mdm-assignment:";
    private static final String ACCEPTED_SUFFIX = ":accepted";
    private static final String RETURN_SUFFIX = ":return";

    private CharacterAssignmentJournalBridge() {}

    public static String threadId(String assignmentId) {
        requireText(assignmentId, "assignmentId");
        return THREAD_PREFIX + assignmentId;
    }

    /** Append an idempotent acceptance event after the ledger records QUEUED -> ACCEPTED. */
    public static void appendAccepted(
            CharacterAssignmentStore.Assignment assignment,
            BuilderConversationJournal journal) {
        requireInputs(assignment, journal);
        String threadId = threadId(assignment.assignmentId);
        String turnId = stableTurnId(assignment.assignmentId, ACCEPTED_SUFFIX);
        if (containsTurn(journal.readThread(threadId), turnId)) return;

        JSONObject body = envelope(assignment, "ACCEPTED");
        put(body, "execution_started", false);
        append(journal, threadId, turnId, participant(assignment.character), null, null,
                BuilderConversationProtocol.TurnType.CONTRIBUTION,
                BuilderConversationProtocol.EvidenceStatus.OBSERVED, body);
    }

    /**
     * Append the structured return report as a Character response. The report is copied
     * into the journal without changing its evidence claims or authorization meaning.
     */
    public static void appendReturnReport(
            CharacterAssignmentStore.Assignment assignment,
            String reportJson,
            BuilderConversationJournal journal) {
        requireInputs(assignment, journal);
        requireText(reportJson, "reportJson");
        String threadId = threadId(assignment.assignmentId);
        String acceptedTurnId = stableTurnId(assignment.assignmentId, ACCEPTED_SUFFIX);
        String returnTurnId = stableTurnId(assignment.assignmentId, RETURN_SUFFIX);
        List<BuilderConversationProtocol.Turn> turns = journal.readThread(threadId);
        if (containsTurn(turns, returnTurnId)) return;
        if (!containsTurn(turns, acceptedTurnId)) {
            appendAccepted(assignment, journal);
            turns = journal.readThread(threadId);
        }

        JSONObject body = envelope(assignment, "RETURN_REPORT");
        try {
            body.put("report", new JSONObject(reportJson));
        } catch (JSONException notObject) {
            throw new IllegalArgumentException("return report must be a JSON object", notObject);
        }
        append(journal, threadId, returnTurnId, participant(assignment.character),
                BuilderConversationProtocol.Participant.USER, acceptedTurnId,
                BuilderConversationProtocol.TurnType.RESPONSE,
                BuilderConversationProtocol.EvidenceStatus.OBSERVED, body);
    }

    private static JSONObject envelope(CharacterAssignmentStore.Assignment assignment, String event) {
        JSONObject body = new JSONObject();
        put(body, "assignment_id", assignment.assignmentId);
        put(body, "character", assignment.character);
        put(body, "event", event);
        return body;
    }

    private static void append(BuilderConversationJournal journal, String threadId, String turnId,
            BuilderConversationProtocol.Participant speaker,
            BuilderConversationProtocol.Participant recipient, String replyTo,
            BuilderConversationProtocol.TurnType type,
            BuilderConversationProtocol.EvidenceStatus evidence, JSONObject body) {
        List<BuilderConversationProtocol.Turn> prior = journal.readThread(threadId);
        long last = prior.isEmpty() ? 0L : prior.get(prior.size() - 1).timestampEpochMillis;
        BuilderConversationProtocol.Turn turn = new BuilderConversationProtocol.Turn(
                BuilderConversationProtocol.CURRENT_VERSION, threadId, turnId, speaker,
                recipient, replyTo, type, evidence,
                Math.max(System.currentTimeMillis(), last + 1L), body.toString());
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
