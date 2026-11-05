package org.yggdrasil.scheduler.builder;

import android.content.Context;

import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
public class BuilderConversationCoordinatorTest {
    private static final String DATABASE = "builder-conversation-coordinator-test.db";
    private BuilderConversationStore store;

    private BuilderConversationStore openStore() {
        store = new BuilderConversationStore(RuntimeEnvironment.getApplication(), DATABASE);
        return store;
    }

    @After public void cleanUp() {
        if (store != null) store.close();
        Context context = RuntimeEnvironment.getApplication();
        context.deleteDatabase(DATABASE);
    }

    @Test public void recordsUserMessageAndRoutesResponsesToSelectedBuilders() {
        BuilderConversationStore journal = openStore();
        BuilderConversationCoordinator coordinator = new BuilderConversationCoordinator(
                journal,
                (role, context, userTurn) -> new BuilderConversationCoordinator.Reply(
                        role.getDisplayName() + " response",
                        BuilderConversationProtocol.EvidenceStatus.PROPOSED));

        BuilderConversationCoordinator.Result result = coordinator.submit(
                "coordinator-thread",
                "Examine the conversation architecture.",
                Arrays.asList(BuilderRole.HUGINN, BuilderRole.KVASIR));

        assertEquals(3, result.appendedTurns.size());
        assertTrue(result.failedRoles.isEmpty());
        assertEquals(BuilderConversationProtocol.Participant.USER,
                result.appendedTurns.get(0).speaker);
        assertEquals(BuilderConversationProtocol.Participant.HUGINN,
                result.appendedTurns.get(1).speaker);
        assertEquals(BuilderConversationProtocol.Participant.KVASIR,
                result.appendedTurns.get(2).speaker);
        assertEquals(BuilderConversationProtocol.Participant.USER,
                result.appendedTurns.get(2).recipient);
        assertEquals(result.userTurnId, result.appendedTurns.get(2).replyToTurnId);

        List<BuilderConversationProtocol.Turn> recovered =
                journal.readThread("coordinator-thread");
        BuilderConversationProtocol.validateThread(recovered);
        assertEquals(3, recovered.size());
    }

    @Test public void oneBuilderFailureDoesNotEraseOtherRepliesOrUserTurn() {
        BuilderConversationStore journal = openStore();
        BuilderConversationCoordinator coordinator = new BuilderConversationCoordinator(
                journal,
                (role, context, userTurn) -> {
                    if (role == BuilderRole.LOKI) throw new IllegalStateException("simulated responder failure");
                    return new BuilderConversationCoordinator.Reply(
                            "Baldr completed a response.",
                            BuilderConversationProtocol.EvidenceStatus.TESTED);
                });

        BuilderConversationCoordinator.Result result = coordinator.submit(
                "partial-thread", "Check this proposal.",
                Arrays.asList(BuilderRole.LOKI, BuilderRole.BALDR));

        assertEquals(2, result.appendedTurns.size());
        assertEquals(Collections.singletonList(BuilderRole.LOKI), result.failedRoles);
        List<BuilderConversationProtocol.Turn> recovered = journal.readThread("partial-thread");
        assertEquals(2, recovered.size());
        assertEquals(BuilderConversationProtocol.Participant.USER, recovered.get(0).speaker);
        assertEquals(BuilderConversationProtocol.Participant.BALDR, recovered.get(1).speaker);
    }

    @Test public void routesBuilderQuestionToNamedPeerAndAllowsPeerResponse() {
        BuilderConversationStore journal = openStore();
        BuilderConversationCoordinator coordinator = new BuilderConversationCoordinator(
                journal,
                (role, context, userTurn) -> {
                    if (role == BuilderRole.HUGINN) {
                        return new BuilderConversationCoordinator.Reply(
                                "Kvasir, verify the source evidence.",
                                BuilderConversationProtocol.EvidenceStatus.PROPOSED,
                                BuilderConversationProtocol.TurnType.QUESTION,
                                BuilderConversationProtocol.Participant.KVASIR,
                                null);
                    }
                    if (role == BuilderRole.KVASIR) {
                        BuilderConversationProtocol.Turn question = context.get(context.size() - 1);
                        assertEquals(BuilderConversationProtocol.Participant.KVASIR,
                                question.recipient);
                        return new BuilderConversationCoordinator.Reply(
                                "The source supports the claim, with one limitation.",
                                BuilderConversationProtocol.EvidenceStatus.OBSERVED,
                                BuilderConversationProtocol.TurnType.RESPONSE,
                                BuilderConversationProtocol.Participant.HUGINN,
                                question.turnId);
                    }
                    throw new IllegalArgumentException("Unexpected Builder role");
                });

        BuilderConversationCoordinator.Result result = coordinator.submit(
                "peer-dialogue-thread", "Investigate this claim.",
                Arrays.asList(BuilderRole.HUGINN, BuilderRole.KVASIR));

        assertTrue(result.failedRoles.isEmpty());
        assertEquals(3, result.appendedTurns.size());
        BuilderConversationProtocol.Turn question = result.appendedTurns.get(1);
        BuilderConversationProtocol.Turn response = result.appendedTurns.get(2);
        assertEquals(BuilderConversationProtocol.TurnType.QUESTION, question.type);
        assertEquals(BuilderConversationProtocol.Participant.KVASIR, question.recipient);
        assertEquals(BuilderConversationProtocol.TurnType.RESPONSE, response.type);
        assertEquals(BuilderConversationProtocol.Participant.HUGINN, response.recipient);
        assertEquals(question.turnId, response.replyToTurnId);
        BuilderConversationProtocol.validateThread(journal.readThread("peer-dialogue-thread"));
    }

    @Test public void rejectsBuilderDecisionTurnsAtReplyConstruction() {
        assertThrows(IllegalArgumentException.class, () ->
                new BuilderConversationCoordinator.Reply(
                        "The project is approved.",
                        BuilderConversationProtocol.EvidenceStatus.PROPOSED,
                        BuilderConversationProtocol.TurnType.DECISION,
                        BuilderConversationProtocol.Participant.USER,
                        null));
    }

    @Test public void rejectsDuplicateRolesBeforeRecordingUserTurn() {
        BuilderConversationStore journal = openStore();
        BuilderConversationCoordinator coordinator = new BuilderConversationCoordinator(
                journal,
                (role, context, userTurn) -> new BuilderConversationCoordinator.Reply(
                        "Unused.", BuilderConversationProtocol.EvidenceStatus.UNKNOWN));

        assertThrows(IllegalArgumentException.class, () -> coordinator.submit(
                "duplicate-role-thread", "Do not record this yet.",
                Arrays.asList(BuilderRole.HUGINN, BuilderRole.HUGINN)));
        assertTrue(journal.listThreadIds().isEmpty());
    }

    @Test public void laterBuilderReceivesEarlierBuilderContext() {
        BuilderConversationStore journal = openStore();
        BuilderConversationCoordinator coordinator = new BuilderConversationCoordinator(
                journal,
                (role, context, userTurn) -> {
                    if (role == BuilderRole.KVASIR) {
                        assertEquals(2, context.size());
                        assertEquals(BuilderRole.HUGINN.name(),
                                context.get(1).speaker.name());
                    }
                    return new BuilderConversationCoordinator.Reply(
                            "Context received.", BuilderConversationProtocol.EvidenceStatus.UNKNOWN);
                });

        coordinator.submit("context-thread", "Coordinate the investigation.",
                Arrays.asList(BuilderRole.HUGINN, BuilderRole.KVASIR));
        assertEquals(3, journal.readThread("context-thread").size());
    }
}
