package org.yggdrasil.scheduler.builder;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Typed, transport-neutral Nine Builder conversation contract. */
public final class BuilderConversationProtocol {
    public static final int CURRENT_VERSION = 2;
    public static final int LEGACY_VERSION = 1;
    public static final int MAX_BODY_LENGTH = 8000;

    /** USER is the director; the remaining participants are the Nine Builders. */
    public enum Participant {
        USER, HUGINN, MUNINN, KVASIR, MARI, HODR, TYR, LOKI, ODIN, BALDR
    }

    public enum TurnType {
        CONTRIBUTION, QUESTION, CHALLENGE, RESPONSE, HANDOFF,
        ACKNOWLEDGEMENT, RECONCILIATION, INTERRUPTION, IMPROVEMENT_INCIDENT, DECISION
    }

    public enum EvidenceStatus {
        PROPOSED, OBSERVED, TESTED, VERIFIED, USER_CONFIRMED, DISPUTED, UNKNOWN
    }

    private BuilderConversationProtocol() {}

    public static final class Turn {
        public final int protocolVersion;
        public final String threadId;
        public final String turnId;
        public final Participant speaker;
        public final Participant recipient;
        public final String replyToTurnId;
        public final TurnType type;
        public final EvidenceStatus evidenceStatus;
        public final long timestampEpochMillis;
        public final String body;

        public Turn(int protocolVersion, String threadId, String turnId,
                    Participant speaker, Participant recipient, String replyToTurnId,
                    TurnType type, EvidenceStatus evidenceStatus,
                    long timestampEpochMillis, String body) {
            if (protocolVersion != CURRENT_VERSION && protocolVersion != LEGACY_VERSION)
                throw new IllegalArgumentException("Unsupported conversation protocol version");
            this.protocolVersion = protocolVersion;
            this.threadId = required(threadId, "threadId");
            this.turnId = required(turnId, "turnId");
            if (speaker == null) throw new IllegalArgumentException("speaker is required");
            this.speaker = speaker;
            this.recipient = recipient;
            this.replyToTurnId = optional(replyToTurnId);
            if (type == null) throw new IllegalArgumentException("type is required");
            this.type = type;
            if (evidenceStatus == null)
                throw new IllegalArgumentException("evidenceStatus is required");
            this.evidenceStatus = evidenceStatus;
            if (timestampEpochMillis <= 0)
                throw new IllegalArgumentException("timestamp must be positive");
            this.timestampEpochMillis = timestampEpochMillis;
            this.body = required(body, "body");
            if (body.length() > MAX_BODY_LENGTH)
                throw new IllegalArgumentException("body exceeds " + MAX_BODY_LENGTH + " characters");
            validateShape(this);
        }
    }

    public static void validateTurn(Turn turn) {
        if (turn == null) throw new IllegalArgumentException("turn is required");
        validateShape(turn);
    }

    /** Validates ordering, unique identity, thread membership, and reply provenance. */
    public static void validateThread(List<Turn> turns) {
        if (turns == null || turns.isEmpty())
            throw new IllegalArgumentException("A conversation thread must contain turns");

        Map<String, Turn> priorTurns = new HashMap<>();
        Set<String> ids = new HashSet<>();
        String threadId = null;
        long previousTimestamp = 0L;

        for (Turn turn : turns) {
            validateTurn(turn);
            if (!ids.add(turn.turnId))
                throw new IllegalArgumentException("Duplicate turn ID: " + turn.turnId);
            if (threadId == null) threadId = turn.threadId;
            else if (!threadId.equals(turn.threadId))
                throw new IllegalArgumentException("All turns must belong to one thread");
            if (turn.timestampEpochMillis <= previousTimestamp)
                throw new IllegalArgumentException("Turn timestamps must increase in thread order");

            if (turn.replyToTurnId != null) {
                Turn referenced = priorTurns.get(turn.replyToTurnId);
                if (referenced == null)
                    throw new IllegalArgumentException("Replies must reference an earlier turn");
                if (turn.type != TurnType.HANDOFF && turn.type != TurnType.INTERRUPTION
                        && turn.type != TurnType.IMPROVEMENT_INCIDENT
                        && turn.recipient != null && turn.recipient != referenced.speaker)
                    throw new IllegalArgumentException("Recipient must match the referenced speaker");
                boolean answersDirectedTurn = turn.type == TurnType.RESPONSE
                        || turn.type == TurnType.ACKNOWLEDGEMENT
                || turn.type == TurnType.INTERRUPTION
                || turn.type == TurnType.IMPROVEMENT_INCIDENT;
                boolean referencedWasDirected = referenced.type == TurnType.QUESTION
                        || referenced.type == TurnType.CHALLENGE
                        || referenced.type == TurnType.HANDOFF;
                if (answersDirectedTurn && referencedWasDirected
                        && turn.speaker != referenced.recipient)
                    throw new IllegalArgumentException("Only the addressed participant may answer or acknowledge this turn");
            }
            priorTurns.put(turn.turnId, turn);
            previousTimestamp = turn.timestampEpochMillis;
        }
    }

    private static void validateShape(Turn turn) {
        boolean needsRecipient = turn.type == TurnType.QUESTION
                || turn.type == TurnType.CHALLENGE
                || turn.type == TurnType.RESPONSE
                || turn.type == TurnType.HANDOFF
                || turn.type == TurnType.ACKNOWLEDGEMENT;
        boolean needsReply = turn.type == TurnType.CHALLENGE
                || turn.type == TurnType.RESPONSE
                || turn.type == TurnType.HANDOFF
                || turn.type == TurnType.ACKNOWLEDGEMENT
                || turn.type == TurnType.RECONCILIATION
                || turn.type == TurnType.INTERRUPTION
                || turn.type == TurnType.IMPROVEMENT_INCIDENT;

        if (needsRecipient && turn.recipient == null)
            throw new IllegalArgumentException(turn.type + " requires a recipient");
        if (turn.recipient == turn.speaker)
            throw new IllegalArgumentException("A participant cannot address a turn to itself");
        if (needsReply && turn.replyToTurnId == null)
            throw new IllegalArgumentException(turn.type + " requires replyToTurnId");
        if (turn.replyToTurnId != null && turn.replyToTurnId.equals(turn.turnId))
            throw new IllegalArgumentException("A turn cannot reply to itself");
    }

    private static String required(String value, String field) {
        if (value == null || value.trim().isEmpty())
            throw new IllegalArgumentException(field + " must not be blank");
        return value.trim();
    }

    private static String optional(String value) {
        return value == null || value.trim().isEmpty() ? null : value.trim();
    }
}
