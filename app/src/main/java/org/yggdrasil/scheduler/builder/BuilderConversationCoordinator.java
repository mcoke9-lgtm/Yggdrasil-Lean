package org.yggdrasil.scheduler.builder;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Runs selected Builder roles in one process against a replaceable response engine.
 * This is orchestration, not proof of independent agents or a model-provider integration.
 */
public final class BuilderConversationCoordinator {
    private static final int MAX_TURNS_PER_BUILDER_PER_SUBMISSION = 2;
    public interface Responder {
        Reply respond(BuilderRole role,
                      List<BuilderConversationProtocol.Turn> threadContext,
                      BuilderConversationProtocol.Turn userTurn) throws Exception;
    }

    public static final class Reply {
        public final String body;
        public final BuilderConversationProtocol.EvidenceStatus evidenceStatus;
        public final BuilderConversationProtocol.TurnType type;
        public final BuilderConversationProtocol.Participant recipient;
        public final String replyToTurnId;

        /** Default reply directed to the user contribution that started this round. */
        public Reply(String body, BuilderConversationProtocol.EvidenceStatus evidenceStatus) {
            this(body, evidenceStatus, BuilderConversationProtocol.TurnType.RESPONSE,
                    BuilderConversationProtocol.Participant.USER, null);
        }

        /** Typed Builder-to-Builder contribution, question, challenge, reply, or handoff. */
        public Reply(String body, BuilderConversationProtocol.EvidenceStatus evidenceStatus,
                     BuilderConversationProtocol.TurnType type,
                     BuilderConversationProtocol.Participant recipient,
                     String replyToTurnId) {
            if (body == null || body.trim().isEmpty())
                throw new IllegalArgumentException("Builder reply must not be blank");
            if (body.length() > BuilderConversationProtocol.MAX_BODY_LENGTH)
                throw new IllegalArgumentException("Builder reply exceeds the protocol body limit");
            if (evidenceStatus == null)
                throw new IllegalArgumentException("Builder reply evidence status is required");
            if (type == null) throw new IllegalArgumentException("Builder turn type is required");
            if (type == BuilderConversationProtocol.TurnType.DECISION)
                throw new IllegalArgumentException("Builders cannot record an authorized decision");
            this.body = body.trim();
            this.evidenceStatus = evidenceStatus;
            this.type = type;
            this.recipient = recipient;
            this.replyToTurnId = replyToTurnId == null || replyToTurnId.trim().isEmpty()
                    ? null : replyToTurnId.trim();
        }
    }

    public static final class Result {
        public final String threadId;
        public final String userTurnId;
        public final List<BuilderConversationProtocol.Turn> appendedTurns;
        public final List<BuilderRole> failedRoles;
        public final List<BuilderRole> unroutedRecipients;
        public final boolean interrupted;
        public final BuilderRole interruptedBy;
        public final BuilderConversationProtocol.TurnType interruptionType;
        public final List<BuilderRole> notRunRoles;

        private Result(String threadId, String userTurnId,
                       List<BuilderConversationProtocol.Turn> appendedTurns,
                       List<BuilderRole> failedRoles,
                       List<BuilderRole> unroutedRecipients,
                       boolean interrupted, BuilderRole interruptedBy,
                       BuilderConversationProtocol.TurnType interruptionType,
                       List<BuilderRole> notRunRoles) {
            this.threadId = threadId;
            this.userTurnId = userTurnId;
            this.appendedTurns = Collections.unmodifiableList(
                    new ArrayList<>(appendedTurns));
            this.failedRoles = Collections.unmodifiableList(
                    new ArrayList<>(failedRoles));
            this.unroutedRecipients = Collections.unmodifiableList(
                    new ArrayList<>(unroutedRecipients));
            this.interrupted = interrupted;
            this.interruptedBy = interruptedBy;
            this.interruptionType = interruptionType;
            this.notRunRoles = Collections.unmodifiableList(new ArrayList<>(notRunRoles));
        }
    }

    private final BuilderConversationJournal store;
    private final Responder responder;

    public BuilderConversationCoordinator(BuilderConversationJournal store, Responder responder) {
        if (store == null) throw new IllegalArgumentException("conversation store is required");
        if (responder == null) throw new IllegalArgumentException("Builder responder is required");
        this.store = store;
        this.responder = responder;
    }

    /**
     * Records one user contribution, then asks each selected Builder in order.
     * Successful replies are committed individually; one failed role does not erase
     * the user's message or replies already committed by other roles.
     */
    public Result submit(String threadId, String userMessage, List<BuilderRole> selectedRoles) {
        if (threadId == null || threadId.trim().isEmpty())
            throw new IllegalArgumentException("threadId must not be blank");
        if (userMessage == null || userMessage.trim().isEmpty())
            throw new IllegalArgumentException("userMessage must not be blank");
        if (userMessage.length() > BuilderConversationProtocol.MAX_BODY_LENGTH)
            throw new IllegalArgumentException("userMessage exceeds the protocol body limit");
        validateRoles(selectedRoles);

        String normalizedThreadId = threadId.trim();
        long userTimestamp = nextTimestamp(store.readThread(normalizedThreadId));
        String userTurnId = UUID.randomUUID().toString();
        BuilderConversationProtocol.Turn userTurn = new BuilderConversationProtocol.Turn(
                BuilderConversationProtocol.CURRENT_VERSION,
                normalizedThreadId,
                userTurnId,
                BuilderConversationProtocol.Participant.USER,
                null,
                null,
                BuilderConversationProtocol.TurnType.CONTRIBUTION,
                BuilderConversationProtocol.EvidenceStatus.UNKNOWN,
                userTimestamp,
                userMessage.trim());

        store.append(userTurn);
        List<BuilderConversationProtocol.Turn> appended = new ArrayList<>();
        List<BuilderRole> failed = new ArrayList<>();
        appended.add(userTurn);

        ArrayDeque<BuilderRole> queue = new ArrayDeque<>();
        Set<BuilderRole> selected = new HashSet<>(selectedRoles);
        Set<BuilderRole> queued = new HashSet<>();
        Map<BuilderRole, Integer> turnCounts = new HashMap<>();
        List<BuilderRole> unrouted = new ArrayList<>();
        boolean interrupted = false;
        BuilderRole interruptedBy = null;
        BuilderConversationProtocol.TurnType interruptionType = null;
        for (BuilderRole role : selectedRoles) {
            queue.addLast(role);
            queued.add(role);
        }

        while (!queue.isEmpty()) {
            BuilderRole role = queue.removeFirst();
            queued.remove(role);
            int roleTurnCount = turnCounts.containsKey(role) ? turnCounts.get(role) : 0;
            if (roleTurnCount >= MAX_TURNS_PER_BUILDER_PER_SUBMISSION) continue;
            turnCounts.put(role, roleTurnCount + 1);

            try {
                List<BuilderConversationProtocol.Turn> context = store.readThread(normalizedThreadId);
                Reply reply = responder.respond(role, context, userTurn);
                if (reply == null) throw new IllegalStateException("Responder returned no reply");
                BuilderRole incidentAuthor = reviewIncidentAuthor(userTurn, context);
                if (incidentAuthor != null && reply.evidenceStatus == BuilderConversationProtocol.EvidenceStatus.VERIFIED) {
                    if (role == incidentAuthor)
                        throw new IllegalArgumentException("The Builder named in the incident cannot certify its own correction");
                    if (role != BuilderRole.BALDR)
                        throw new IllegalArgumentException("Only an unaffected Baldr review may mark an improvement correction VERIFIED");
                }

                long timestamp = nextTimestamp(context);
                BuilderConversationProtocol.TurnType turnType = reply.type;
                BuilderConversationProtocol.Participant recipient = reply.recipient;
                String replyToTurnId = reply.replyToTurnId;
                if (turnType == BuilderConversationProtocol.TurnType.RESPONSE
                        && replyToTurnId == null) {
                    replyToTurnId = userTurnId;
                }
                BuilderConversationProtocol.Turn response = new BuilderConversationProtocol.Turn(
                        BuilderConversationProtocol.CURRENT_VERSION,
                        normalizedThreadId,
                        UUID.randomUUID().toString(),
                        BuilderConversationProtocol.Participant.valueOf(role.name()),
                        recipient,
                        replyToTurnId,
                        turnType,
                        reply.evidenceStatus,
                        timestamp,
                        reply.body);
                store.append(response);
                appended.add(response);

                BuilderRole nextRole = builderRoleFor(recipient);
                if (reply.type == BuilderConversationProtocol.TurnType.INTERRUPTION
                        || reply.type == BuilderConversationProtocol.TurnType.IMPROVEMENT_INCIDENT) {
                    interrupted = true;
                    interruptedBy = role;
                    interruptionType = reply.type;
                    break;
                }

                if (nextRole != null) {
                    int nextCount = turnCounts.containsKey(nextRole) ? turnCounts.get(nextRole) : 0;
                    if (!selected.contains(nextRole) || nextCount >= MAX_TURNS_PER_BUILDER_PER_SUBMISSION) {
                        if (!unrouted.contains(nextRole)) unrouted.add(nextRole);
                    } else if (queued.add(nextRole)) {
                        queue.addLast(nextRole);
                    }
                }
            } catch (Exception failure) {
                failed.add(role);
            }
        }
        List<BuilderRole> notRun = new ArrayList<>();
        if (interrupted) {
            for (BuilderRole selectedRole : selectedRoles) {
                if (!turnCounts.containsKey(selectedRole)) notRun.add(selectedRole);
            }
        }
        return new Result(normalizedThreadId, userTurnId, appended, failed, unrouted,
                interrupted, interruptedBy, interruptionType, notRun);
    }

    /** Returns the author of the referenced incident when this is a review round. */
    private static BuilderRole reviewIncidentAuthor(
            BuilderConversationProtocol.Turn userTurn,
            List<BuilderConversationProtocol.Turn> context) {
        String marker = "BUILDER_IMPROVEMENT_REVIEW_MODE\\nincident_turn_id=";
        int markerIndex = userTurn.body.indexOf(marker);
        if (markerIndex < 0) return null;
        int start = markerIndex + marker.length();
        int end = userTurn.body.indexOf('\\n', start);
        if (end < 0) end = userTurn.body.length();
        String incidentId = userTurn.body.substring(start, end).trim();
        for (BuilderConversationProtocol.Turn turn : context) {
            if (turn.turnId.equals(incidentId)
                    && turn.type == BuilderConversationProtocol.TurnType.IMPROVEMENT_INCIDENT) {
                BuilderRole author = builderRoleFor(turn.speaker);
                if (author == null)
                    throw new IllegalArgumentException("Improvement incident author must be a Builder");
                return author;
            }
        }
        throw new IllegalArgumentException("Improvement review references no recorded incident in this thread");
    }

    private static BuilderRole builderRoleFor(BuilderConversationProtocol.Participant participant) {
        if (participant == null || participant == BuilderConversationProtocol.Participant.USER)
            return null;
        try {
            return BuilderRole.valueOf(participant.name());
        } catch (IllegalArgumentException notBuilder) {
            return null;
        }
    }

    private static void validateRoles(List<BuilderRole> roles) {
        if (roles == null || roles.isEmpty())
            throw new IllegalArgumentException("Select at least one Builder");
        Set<BuilderRole> unique = new HashSet<>();
        for (BuilderRole role : roles) {
            if (role == null) throw new IllegalArgumentException("Builder role must not be null");
            if (!unique.add(role))
                throw new IllegalArgumentException("Duplicate Builder role: " + role);
        }
    }

    private static long nextTimestamp(List<BuilderConversationProtocol.Turn> context) {
        long last = context.isEmpty() ? 0L
                : context.get(context.size() - 1).timestampEpochMillis;
        return Math.max(System.currentTimeMillis(), last + 1L);
    }
}
