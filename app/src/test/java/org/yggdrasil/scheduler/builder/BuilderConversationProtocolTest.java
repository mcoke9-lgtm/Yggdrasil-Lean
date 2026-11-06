package org.yggdrasil.scheduler.builder;

import org.junit.Test;
import java.util.Arrays;
import java.util.Collections;
import static org.junit.Assert.*;

public class BuilderConversationProtocolTest {
    private BuilderConversationProtocol.Turn turn(
            String id, BuilderConversationProtocol.Participant speaker, BuilderConversationProtocol.Participant recipient, String replyTo,
            BuilderConversationProtocol.TurnType type, long time) {
        return new BuilderConversationProtocol.Turn(
                BuilderConversationProtocol.CURRENT_VERSION, "thread-1", id,
                speaker, recipient, replyTo, type,
                BuilderConversationProtocol.EvidenceStatus.PROPOSED, time, "A bounded contribution.");
    }

    @Test public void recordsUserDirectionAndBuilderResponse() {
        BuilderConversationProtocol.Turn direction = turn("t1",
                BuilderConversationProtocol.Participant.USER,
                BuilderConversationProtocol.Participant.HUGINN, null,
                BuilderConversationProtocol.TurnType.QUESTION, 100);
        BuilderConversationProtocol.Turn response = turn("t2",
                BuilderConversationProtocol.Participant.HUGINN,
                BuilderConversationProtocol.Participant.USER, "t1",
                BuilderConversationProtocol.TurnType.RESPONSE, 200);
        BuilderConversationProtocol.validateThread(Arrays.asList(direction, response));
        assertEquals(BuilderConversationProtocol.Participant.USER, direction.speaker);
        assertEquals(BuilderConversationProtocol.Participant.USER, response.recipient);
    }

    @Test public void acceptsLegacyBuilderOnlyTurnsForRecovery() {
        BuilderConversationProtocol.Turn legacy = new BuilderConversationProtocol.Turn(
                BuilderConversationProtocol.LEGACY_VERSION, "thread-legacy", "t1",
                BuilderConversationProtocol.Participant.HUGINN, null, null,
                BuilderConversationProtocol.TurnType.CONTRIBUTION,
                BuilderConversationProtocol.EvidenceStatus.UNKNOWN, 100, "Legacy record.");
        BuilderConversationProtocol.validateThread(Collections.singletonList(legacy));
        assertEquals(BuilderConversationProtocol.LEGACY_VERSION, legacy.protocolVersion);
    }

    @Test public void acceptsDirectBuilderQuestionAndResponse() {
        BuilderConversationProtocol.Turn question = turn("t1", BuilderConversationProtocol.Participant.HUGINN,
                BuilderConversationProtocol.Participant.KVASIR, null, BuilderConversationProtocol.TurnType.QUESTION, 100);
        BuilderConversationProtocol.Turn response = turn("t2", BuilderConversationProtocol.Participant.KVASIR,
                BuilderConversationProtocol.Participant.HUGINN, "t1", BuilderConversationProtocol.TurnType.RESPONSE, 200);
        BuilderConversationProtocol.validateThread(Arrays.asList(question, response));
    }

    @Test public void acceptsChallengeAndReconciliationWithoutErasingDisagreement() {
        BuilderConversationProtocol.Turn claim = turn("t1", BuilderConversationProtocol.Participant.HODR,
                null, null, BuilderConversationProtocol.TurnType.CONTRIBUTION, 100);
        BuilderConversationProtocol.Turn challenge = turn("t2", BuilderConversationProtocol.Participant.LOKI,
                BuilderConversationProtocol.Participant.HODR, "t1", BuilderConversationProtocol.TurnType.CHALLENGE, 200);
        BuilderConversationProtocol.Turn reply = turn("t3", BuilderConversationProtocol.Participant.HODR,
                BuilderConversationProtocol.Participant.LOKI, "t2", BuilderConversationProtocol.TurnType.RESPONSE, 300);
        BuilderConversationProtocol.Turn reconcile = turn("t4", BuilderConversationProtocol.Participant.ODIN,
                null, "t2", BuilderConversationProtocol.TurnType.RECONCILIATION, 400);
        BuilderConversationProtocol.validateThread(Arrays.asList(claim, challenge, reply, reconcile));
    }

    @Test public void acceptsTaskHandoffAndAcknowledgement() {
        BuilderConversationProtocol.Turn request = turn("t1", BuilderConversationProtocol.Participant.MARI,
                null, null, BuilderConversationProtocol.TurnType.CONTRIBUTION, 100);
        BuilderConversationProtocol.Turn handoff = turn("t2", BuilderConversationProtocol.Participant.MARI,
                BuilderConversationProtocol.Participant.MUNINN, "t1", BuilderConversationProtocol.TurnType.HANDOFF, 200);
        BuilderConversationProtocol.Turn receipt = turn("t3", BuilderConversationProtocol.Participant.MUNINN,
                BuilderConversationProtocol.Participant.MARI, "t2", BuilderConversationProtocol.TurnType.ACKNOWLEDGEMENT, 300);
        BuilderConversationProtocol.validateThread(Arrays.asList(request, handoff, receipt));
    }

    @Test public void rejectsResponseFromBuilderWhoWasNotAddressed() {
        BuilderConversationProtocol.Turn question = turn("t1",
                BuilderConversationProtocol.Participant.HUGINN,
                BuilderConversationProtocol.Participant.KVASIR, null,
                BuilderConversationProtocol.TurnType.QUESTION, 100);
        BuilderConversationProtocol.Turn wrongResponder = turn("t2",
                BuilderConversationProtocol.Participant.MARI,
                BuilderConversationProtocol.Participant.HUGINN, "t1",
                BuilderConversationProtocol.TurnType.RESPONSE, 200);
        assertThrows(IllegalArgumentException.class, () ->
                BuilderConversationProtocol.validateThread(Arrays.asList(question, wrongResponder)));
    }

    @Test public void rejectsChallengeWithoutRecipientOrReplyTarget() {
        assertThrows(IllegalArgumentException.class, () -> turn("t1", BuilderConversationProtocol.Participant.LOKI,
                null, null, BuilderConversationProtocol.TurnType.CHALLENGE, 100));
    }

    @Test public void rejectsSelfAddressing() {
        assertThrows(IllegalArgumentException.class, () -> turn("t1", BuilderConversationProtocol.Participant.MUNINN,
                BuilderConversationProtocol.Participant.MUNINN, null, BuilderConversationProtocol.TurnType.QUESTION, 100));
    }

    @Test public void rejectsReplyToUnknownOrLaterTurn() {
        BuilderConversationProtocol.Turn response = turn("t1", BuilderConversationProtocol.Participant.KVASIR,
                BuilderConversationProtocol.Participant.HUGINN, "missing", BuilderConversationProtocol.TurnType.RESPONSE, 100);
        assertThrows(IllegalArgumentException.class,
                () -> BuilderConversationProtocol.validateThread(Collections.singletonList(response)));
    }

    @Test public void rejectsRecipientThatDoesNotOwnReferencedTurn() {
        BuilderConversationProtocol.Turn first = turn("t1", BuilderConversationProtocol.Participant.HUGINN,
                null, null, BuilderConversationProtocol.TurnType.CONTRIBUTION, 100);
        BuilderConversationProtocol.Turn response = turn("t2", BuilderConversationProtocol.Participant.KVASIR,
                BuilderConversationProtocol.Participant.MARI, "t1", BuilderConversationProtocol.TurnType.RESPONSE, 200);
        assertThrows(IllegalArgumentException.class,
                () -> BuilderConversationProtocol.validateThread(Arrays.asList(first, response)));
    }

    @Test public void rejectsDuplicateIdsMixedThreadsAndOutOfOrderTime() {
        BuilderConversationProtocol.Turn first = turn("t1", BuilderConversationProtocol.Participant.HUGINN,
                null, null, BuilderConversationProtocol.TurnType.CONTRIBUTION, 100);
        BuilderConversationProtocol.Turn duplicate = turn("t1", BuilderConversationProtocol.Participant.MUNINN,
                null, null, BuilderConversationProtocol.TurnType.CONTRIBUTION, 200);
        assertThrows(IllegalArgumentException.class,
                () -> BuilderConversationProtocol.validateThread(Arrays.asList(first, duplicate)));

        BuilderConversationProtocol.Turn otherThread = new BuilderConversationProtocol.Turn(BuilderConversationProtocol.CURRENT_VERSION,
                "thread-2", "t2", BuilderConversationProtocol.Participant.MUNINN, null, null,
                BuilderConversationProtocol.TurnType.CONTRIBUTION,
                BuilderConversationProtocol.EvidenceStatus.UNKNOWN, 200, "Another thread.");
        assertThrows(IllegalArgumentException.class,
                () -> BuilderConversationProtocol.validateThread(Arrays.asList(first, otherThread)));

        BuilderConversationProtocol.Turn earlier = turn("t2", BuilderConversationProtocol.Participant.MUNINN,
                null, null, BuilderConversationProtocol.TurnType.CONTRIBUTION, 99);
        assertThrows(IllegalArgumentException.class,
                () -> BuilderConversationProtocol.validateThread(Arrays.asList(first, earlier)));
    }

    @Test public void preservesEvidenceStatusAsAnExplicitField() {
        BuilderConversationProtocol.Turn verified = new BuilderConversationProtocol.Turn(BuilderConversationProtocol.CURRENT_VERSION,
                "thread-1", "t1", BuilderConversationProtocol.Participant.BALDR, null, null,
                BuilderConversationProtocol.TurnType.CONTRIBUTION,
                BuilderConversationProtocol.EvidenceStatus.VERIFIED, 100, "The test passed.");
        assertEquals(BuilderConversationProtocol.EvidenceStatus.VERIFIED, verified.evidenceStatus);
    }

    @Test public void rejectsUnsupportedVersionAndOversizedBody() {
        assertThrows(IllegalArgumentException.class, () -> new BuilderConversationProtocol.Turn(
                3, "thread-1", "t1", BuilderConversationProtocol.Participant.HUGINN, null, null,
                BuilderConversationProtocol.TurnType.CONTRIBUTION,
                BuilderConversationProtocol.EvidenceStatus.PROPOSED, 100, "Body"));
        StringBuilder oversized = new StringBuilder();
        for (int i = 0; i <= BuilderConversationProtocol.MAX_BODY_LENGTH; i++) oversized.append('x');
        assertThrows(IllegalArgumentException.class, () -> new BuilderConversationProtocol.Turn(
                1, "thread-1", "t2", BuilderConversationProtocol.Participant.HUGINN, null, null,
                BuilderConversationProtocol.TurnType.CONTRIBUTION,
                BuilderConversationProtocol.EvidenceStatus.PROPOSED, 200, oversized.toString()));
    }
}
