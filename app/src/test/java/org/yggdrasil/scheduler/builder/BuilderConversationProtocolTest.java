package org.yggdrasil.scheduler.builder;

import org.junit.Test;
import java.util.Arrays;
import java.util.Collections;
import static org.junit.Assert.*;

public class BuilderConversationProtocolTest {
    private BuilderConversationProtocol.Turn turn(
            String id, BuilderRole speaker, BuilderRole recipient, String replyTo,
            BuilderConversationProtocol.TurnType type, long time) {
        return new BuilderConversationProtocol.Turn(
                BuilderConversationProtocol.CURRENT_VERSION, "thread-1", id,
                speaker, recipient, replyTo, type,
                BuilderConversationProtocol.EvidenceStatus.PROPOSED, time, "A bounded contribution.");
    }

    @Test public void acceptsDirectBuilderQuestionAndResponse() {
        BuilderConversationProtocol.Turn question = turn("t1", BuilderRole.HUGINN,
                BuilderRole.KVASIR, null, BuilderConversationProtocol.TurnType.QUESTION, 100);
        BuilderConversationProtocol.Turn response = turn("t2", BuilderRole.KVASIR,
                BuilderRole.HUGINN, "t1", BuilderConversationProtocol.TurnType.RESPONSE, 200);
        BuilderConversationProtocol.validateThread(Arrays.asList(question, response));
    }

    @Test public void acceptsChallengeAndReconciliationWithoutErasingDisagreement() {
        BuilderConversationProtocol.Turn claim = turn("t1", BuilderRole.HODR,
                null, null, BuilderConversationProtocol.TurnType.CONTRIBUTION, 100);
        BuilderConversationProtocol.Turn challenge = turn("t2", BuilderRole.LOKI,
                BuilderRole.HODR, "t1", BuilderConversationProtocol.TurnType.CHALLENGE, 200);
        BuilderConversationProtocol.Turn reply = turn("t3", BuilderRole.HODR,
                BuilderRole.LOKI, "t2", BuilderConversationProtocol.TurnType.RESPONSE, 300);
        BuilderConversationProtocol.Turn reconcile = turn("t4", BuilderRole.ODIN,
                null, "t2", BuilderConversationProtocol.TurnType.RECONCILIATION, 400);
        BuilderConversationProtocol.validateThread(Arrays.asList(claim, challenge, reply, reconcile));
    }

    @Test public void acceptsTaskHandoffAndAcknowledgement() {
        BuilderConversationProtocol.Turn request = turn("t1", BuilderRole.MARI,
                null, null, BuilderConversationProtocol.TurnType.CONTRIBUTION, 100);
        BuilderConversationProtocol.Turn handoff = turn("t2", BuilderRole.MARI,
                BuilderRole.MUNINN, "t1", BuilderConversationProtocol.TurnType.HANDOFF, 200);
        BuilderConversationProtocol.Turn receipt = turn("t3", BuilderRole.MUNINN,
                BuilderRole.MARI, "t2", BuilderConversationProtocol.TurnType.ACKNOWLEDGEMENT, 300);
        BuilderConversationProtocol.validateThread(Arrays.asList(request, handoff, receipt));
    }

    @Test public void rejectsChallengeWithoutRecipientOrReplyTarget() {
        assertThrows(IllegalArgumentException.class, () -> turn("t1", BuilderRole.LOKI,
                null, null, BuilderConversationProtocol.TurnType.CHALLENGE, 100));
    }

    @Test public void rejectsSelfAddressing() {
        assertThrows(IllegalArgumentException.class, () -> turn("t1", BuilderRole.MUNINN,
                BuilderRole.MUNINN, null, BuilderConversationProtocol.TurnType.QUESTION, 100));
    }

    @Test public void rejectsReplyToUnknownOrLaterTurn() {
        BuilderConversationProtocol.Turn response = turn("t1", BuilderRole.KVASIR,
                BuilderRole.HUGINN, "missing", BuilderConversationProtocol.TurnType.RESPONSE, 100);
        assertThrows(IllegalArgumentException.class,
                () -> BuilderConversationProtocol.validateThread(Collections.singletonList(response)));
    }

    @Test public void rejectsRecipientThatDoesNotOwnReferencedTurn() {
        BuilderConversationProtocol.Turn first = turn("t1", BuilderRole.HUGINN,
                null, null, BuilderConversationProtocol.TurnType.CONTRIBUTION, 100);
        BuilderConversationProtocol.Turn response = turn("t2", BuilderRole.KVASIR,
                BuilderRole.MARI, "t1", BuilderConversationProtocol.TurnType.RESPONSE, 200);
        assertThrows(IllegalArgumentException.class,
                () -> BuilderConversationProtocol.validateThread(Arrays.asList(first, response)));
    }

    @Test public void rejectsDuplicateIdsMixedThreadsAndOutOfOrderTime() {
        BuilderConversationProtocol.Turn first = turn("t1", BuilderRole.HUGINN,
                null, null, BuilderConversationProtocol.TurnType.CONTRIBUTION, 100);
        BuilderConversationProtocol.Turn duplicate = turn("t1", BuilderRole.MUNINN,
                null, null, BuilderConversationProtocol.TurnType.CONTRIBUTION, 200);
        assertThrows(IllegalArgumentException.class,
                () -> BuilderConversationProtocol.validateThread(Arrays.asList(first, duplicate)));

        BuilderConversationProtocol.Turn otherThread = new BuilderConversationProtocol.Turn(1,
                "thread-2", "t2", BuilderRole.MUNINN, null, null,
                BuilderConversationProtocol.TurnType.CONTRIBUTION,
                BuilderConversationProtocol.EvidenceStatus.UNKNOWN, 200, "Another thread.");
        assertThrows(IllegalArgumentException.class,
                () -> BuilderConversationProtocol.validateThread(Arrays.asList(first, otherThread)));

        BuilderConversationProtocol.Turn earlier = turn("t2", BuilderRole.MUNINN,
                null, null, BuilderConversationProtocol.TurnType.CONTRIBUTION, 99);
        assertThrows(IllegalArgumentException.class,
                () -> BuilderConversationProtocol.validateThread(Arrays.asList(first, earlier)));
    }

    @Test public void preservesEvidenceStatusAsAnExplicitField() {
        BuilderConversationProtocol.Turn verified = new BuilderConversationProtocol.Turn(1,
                "thread-1", "t1", BuilderRole.BALDR, null, null,
                BuilderConversationProtocol.TurnType.CONTRIBUTION,
                BuilderConversationProtocol.EvidenceStatus.VERIFIED, 100, "The test passed.");
        assertEquals(BuilderConversationProtocol.EvidenceStatus.VERIFIED, verified.evidenceStatus);
    }

    @Test public void rejectsUnsupportedVersionAndOversizedBody() {
        assertThrows(IllegalArgumentException.class, () -> new BuilderConversationProtocol.Turn(
                2, "thread-1", "t1", BuilderRole.HUGINN, null, null,
                BuilderConversationProtocol.TurnType.CONTRIBUTION,
                BuilderConversationProtocol.EvidenceStatus.PROPOSED, 100, "Body"));
        StringBuilder oversized = new StringBuilder();
        for (int i = 0; i <= BuilderConversationProtocol.MAX_BODY_LENGTH; i++) oversized.append('x');
        assertThrows(IllegalArgumentException.class, () -> new BuilderConversationProtocol.Turn(
                1, "thread-1", "t2", BuilderRole.HUGINN, null, null,
                BuilderConversationProtocol.TurnType.CONTRIBUTION,
                BuilderConversationProtocol.EvidenceStatus.PROPOSED, 200, oversized.toString()));
    }
}
