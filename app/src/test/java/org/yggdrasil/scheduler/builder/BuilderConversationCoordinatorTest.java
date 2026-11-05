package org.yggdrasil.scheduler.builder;

import android.content.Context;
import org.json.JSONObject;

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
        java.util.Map<BuilderRole, Integer> calls = new java.util.EnumMap<>(BuilderRole.class);
        BuilderConversationCoordinator coordinator = new BuilderConversationCoordinator(
                journal,
                (role, context, userTurn) -> {
                    int call = calls.containsKey(role) ? calls.get(role) + 1 : 1;
                    calls.put(role, call);
                    if (role == BuilderRole.HUGINN && call == 1) {
                        return new BuilderConversationCoordinator.Reply(
                                "Kvasir, verify the source evidence.",
                                BuilderConversationProtocol.EvidenceStatus.PROPOSED,
                                BuilderConversationProtocol.TurnType.QUESTION,
                                BuilderConversationProtocol.Participant.KVASIR,
                                null);
                    }
                    if (role == BuilderRole.HUGINN && call == 2) {
                        return new BuilderConversationCoordinator.Reply(
                                "Kvasir's evidence review is incorporated.",
                                BuilderConversationProtocol.EvidenceStatus.TESTED);
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
        assertTrue(result.unroutedRecipients.isEmpty());
        assertEquals(4, result.appendedTurns.size());
        assertEquals(Integer.valueOf(2), calls.get(BuilderRole.HUGINN));
        assertEquals(Integer.valueOf(1), calls.get(BuilderRole.KVASIR));
        BuilderConversationProtocol.Turn question = result.appendedTurns.get(1);
        BuilderConversationProtocol.Turn response = result.appendedTurns.get(2);
        assertEquals(BuilderConversationProtocol.TurnType.QUESTION, question.type);
        assertEquals(BuilderConversationProtocol.Participant.KVASIR, question.recipient);
        assertEquals(BuilderConversationProtocol.TurnType.RESPONSE, response.type);
        assertEquals(BuilderConversationProtocol.Participant.HUGINN, response.recipient);
        assertEquals(question.turnId, response.replyToTurnId);
        BuilderConversationProtocol.validateThread(journal.readThread("peer-dialogue-thread"));
    }

    @Test public void improvementReviewRestrictsVerifiedClaimsToUnaffectedBaldr() throws Exception {
        BuilderConversationStore journal = openStore();
        BuilderConversationProtocol.Turn origin = new BuilderConversationProtocol.Turn(
                BuilderConversationProtocol.CURRENT_VERSION, "review-guard", "origin",
                BuilderConversationProtocol.Participant.USER, null, null,
                BuilderConversationProtocol.TurnType.CONTRIBUTION,
                BuilderConversationProtocol.EvidenceStatus.UNKNOWN, 100, "Original task.");
        journal.append(origin);
        JSONObject incidentBody = new JSONObject();
        incidentBody.put("incident_summary", "Kvasir used a poor method.");
        incidentBody.put("observed_behavior", "Recommended a default route without checking the project scheduler.");
        incidentBody.put("expected_behavior", "Check the project-owned scheduler first.");
        incidentBody.put("evidence", "The scheduler is recorded as an existing project capability.");
        incidentBody.put("unknowns", "Deployment and GitHub access.");
        incidentBody.put("suspected_causes", "Context or reasoning failure.");
        incidentBody.put("proposed_next_step", "Independent review.");
        incidentBody.put("status", "OPEN_REVIEW");
        journal.append(new BuilderConversationProtocol.Turn(
                BuilderConversationProtocol.CURRENT_VERSION, "review-guard", "incident",
                BuilderConversationProtocol.Participant.KVASIR,
                BuilderConversationProtocol.Participant.USER, "origin",
                BuilderConversationProtocol.TurnType.IMPROVEMENT_INCIDENT,
                BuilderConversationProtocol.EvidenceStatus.OBSERVED, 200, incidentBody.toString()));

        BuilderConversationCoordinator coordinator = new BuilderConversationCoordinator(
                journal,
                (role, context, userTurn) -> new BuilderConversationCoordinator.Reply(
                        role.getDisplayName() + " says verified.",
                        BuilderConversationProtocol.EvidenceStatus.VERIFIED));
        BuilderConversationCoordinator.Result result = coordinator.submit(
                "review-guard",
                "BUILDER_IMPROVEMENT_REVIEW_MODE\nincident_turn_id=incident\nReview the correction.",
                Arrays.asList(BuilderRole.KVASIR, BuilderRole.HUGINN, BuilderRole.BALDR));

        assertEquals(Arrays.asList(BuilderRole.KVASIR, BuilderRole.HUGINN), result.failedRoles);
        BuilderConversationProtocol.Turn baldr = result.appendedTurns.get(result.appendedTurns.size() - 1);
        assertEquals(BuilderConversationProtocol.Participant.BALDR, baldr.speaker);
        assertEquals(BuilderConversationProtocol.EvidenceStatus.VERIFIED, baldr.evidenceStatus);
        BuilderConversationProtocol.validateThread(journal.readThread("review-guard"));
    }

    @Test public void coordinatesCorrectionPlanThroughVerifiedAndUserAdoptedMilestones() throws Exception {
        BuilderConversationStore journal = openStore();
        BuilderConversationProtocol.Turn origin = new BuilderConversationProtocol.Turn(
                BuilderConversationProtocol.CURRENT_VERSION, "correction-round", "origin",
                BuilderConversationProtocol.Participant.USER, null, null,
                BuilderConversationProtocol.TurnType.CONTRIBUTION,
                BuilderConversationProtocol.EvidenceStatus.UNKNOWN, 100, "Original task.");
        journal.append(origin);
        JSONObject incidentBody = new JSONObject();
        incidentBody.put("incident_summary", "Unsuitable methodology was recommended.");
        incidentBody.put("observed_behavior", "The independent scheduler was not checked.");
        incidentBody.put("expected_behavior", "Check the project scheduler first.");
        incidentBody.put("evidence", "The project scheduler prototype is recorded.");
        incidentBody.put("unknowns", "Live deployment and access.");
        incidentBody.put("suspected_causes", "Context or reasoning failure.");
        incidentBody.put("proposed_next_step", "Review and correct the method.");
        incidentBody.put("status", "OPEN_REVIEW");
        journal.append(new BuilderConversationProtocol.Turn(
                BuilderConversationProtocol.CURRENT_VERSION, "correction-round", "incident-1",
                BuilderConversationProtocol.Participant.KVASIR,
                BuilderConversationProtocol.Participant.USER, "origin",
                BuilderConversationProtocol.TurnType.IMPROVEMENT_INCIDENT,
                BuilderConversationProtocol.EvidenceStatus.OBSERVED, 200, incidentBody.toString()));

        BuilderConversationCoordinator coordinator = new BuilderConversationCoordinator(
                journal, (role, context, userTurn) -> {
                    String previous = context.get(context.size() - 1).turnId;
                    if (role == BuilderRole.HUGINN) {
                        JSONObject plan = new JSONObject();
                        plan.put("correction_id", "corr-round-1");
                        plan.put("incident_turn_id", "incident-1");
                        plan.put("objective", "Use the independent scheduler before provider task capacity.");
                        plan.put("acceptance_criteria", "A live scheduler capability check and monitor run are evidenced.");
                        plan.put("steps", "Inspect deployment, verify repository access, run a bounded monitor.");
                        plan.put("owner", "Höðr implements; Kvasir coordinates.");
                        plan.put("rollback_condition", "Stop if access or authorization is absent.");
                        plan.put("status", "PROPOSED");
                        return new BuilderConversationCoordinator.Reply(plan.toString(),
                                BuilderConversationProtocol.EvidenceStatus.PROPOSED,
                                BuilderConversationProtocol.TurnType.CORRECTION_PLAN,
                                BuilderConversationProtocol.Participant.USER, "incident-1");
                    }
                    String stage = role == BuilderRole.HODR ? "IMPLEMENTED"
                            : role == BuilderRole.MUNINN ? "TESTED" : "VERIFIED";
                    JSONObject update = new JSONObject();
                    update.put("correction_id", "corr-round-1");
                    update.put("incident_turn_id", "incident-1");
                    update.put("stage", stage);
                    update.put("evidence", "Evidence for " + stage + " milestone is recorded.");
                    update.put("artifact_reference", "artifact-" + stage.toLowerCase());
                    update.put("acceptance_criteria_result", "Acceptance criteria result for " + stage + " is recorded.");
                    update.put("unresolved", "Live deployment remains unverified.");
                    return new BuilderConversationCoordinator.Reply(update.toString(),
                            BuilderConversationProtocol.EvidenceStatus.valueOf(stage),
                            BuilderConversationProtocol.TurnType.CORRECTION_UPDATE,
                            BuilderConversationProtocol.Participant.USER, previous);
                });

        BuilderConversationCoordinator.Result result = coordinator.submit(
                "correction-round",
                "BUILDER_IMPROVEMENT_REVIEW_MODE\nincident_turn_id=incident-1\nPropose and evidence a correction.",
                Arrays.asList(BuilderRole.HUGINN, BuilderRole.HODR, BuilderRole.MUNINN, BuilderRole.BALDR));

        assertTrue(result.failedRoles.toString(), result.failedRoles.isEmpty());
        assertEquals(5, result.appendedTurns.size());
        assertEquals("corr-round-1", BuilderCorrectionLedger.latestAdoptableCorrection(
                journal.readThread("correction-round")));
        BuilderConversationProtocol.Turn adopted = BuilderCorrectionLedger.recordUserAdoption(
                journal, "correction-round", "corr-round-1");
        assertEquals(BuilderConversationProtocol.EvidenceStatus.ADOPTED, adopted.evidenceStatus);
        BuilderConversationProtocol.validateThread(journal.readThread("correction-round"));
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

    @Test public void returnsToEarlierBuilderForBoundedPeerFollowUp() {
        BuilderConversationStore journal = openStore();
        java.util.Map<BuilderRole, Integer> calls = new java.util.EnumMap<>(BuilderRole.class);
        BuilderConversationCoordinator coordinator = new BuilderConversationCoordinator(
                journal,
                (role, context, userTurn) -> {
                    int call = calls.containsKey(role) ? calls.get(role) + 1 : 1;
                    calls.put(role, call);
                    if (role == BuilderRole.HUGINN && call == 1) {
                        return new BuilderConversationCoordinator.Reply(
                                "Kvasir, examine this evidence.",
                                BuilderConversationProtocol.EvidenceStatus.PROPOSED,
                                BuilderConversationProtocol.TurnType.QUESTION,
                                BuilderConversationProtocol.Participant.KVASIR, null);
                    }
                    if (role == BuilderRole.KVASIR) {
                        BuilderConversationProtocol.Turn question = context.get(context.size() - 1);
                        return new BuilderConversationCoordinator.Reply(
                                "The evidence has a limitation.",
                                BuilderConversationProtocol.EvidenceStatus.OBSERVED,
                                BuilderConversationProtocol.TurnType.RESPONSE,
                                BuilderConversationProtocol.Participant.HUGINN, question.turnId);
                    }
                    if (role == BuilderRole.HUGINN && call == 2) {
                        return new BuilderConversationCoordinator.Reply(
                                "I have incorporated that limitation.",
                                BuilderConversationProtocol.EvidenceStatus.TESTED);
                    }
                    throw new IllegalStateException("Unexpected additional turn");
                });

        BuilderConversationCoordinator.Result result = coordinator.submit(
                "bounded-peer-round", "Review this evidence.",
                Arrays.asList(BuilderRole.HUGINN, BuilderRole.KVASIR));

        assertTrue(result.failedRoles.isEmpty());
        assertTrue(result.unroutedRecipients.isEmpty());
        assertEquals(Integer.valueOf(2), calls.get(BuilderRole.HUGINN));
        assertEquals(Integer.valueOf(1), calls.get(BuilderRole.KVASIR));
        assertEquals(4, journal.readThread("bounded-peer-round").size());
        BuilderConversationProtocol.validateThread(journal.readThread("bounded-peer-round"));
    }

    @Test public void reportsPeerRecipientThatWasNotSelected() {
        BuilderConversationStore journal = openStore();
        BuilderConversationCoordinator coordinator = new BuilderConversationCoordinator(
                journal,
                (role, context, userTurn) -> new BuilderConversationCoordinator.Reply(
                        "Mari should review this interface.",
                        BuilderConversationProtocol.EvidenceStatus.PROPOSED,
                        BuilderConversationProtocol.TurnType.HANDOFF,
                        BuilderConversationProtocol.Participant.MARI,
                        userTurn.turnId));

        BuilderConversationCoordinator.Result result = coordinator.submit(
                "unrouted-peer", "Review the interface.",
                Collections.singletonList(BuilderRole.HUGINN));

        assertEquals(Collections.singletonList(BuilderRole.MARI), result.unroutedRecipients);
        assertEquals(2, journal.readThread("unrouted-peer").size());
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

    @Test public void interruptionStopsRemainingBuildersAndPreservesTheInterruptionTurn() {
        BuilderConversationStore journal = openStore();
        java.util.List<BuilderRole> invoked = new java.util.ArrayList<>();
        BuilderConversationCoordinator coordinator = new BuilderConversationCoordinator(
                journal,
                (role, context, userTurn) -> {
                    invoked.add(role);
                    assertEquals(userTurn.turnId, context.get(0).turnId);
                    return new BuilderConversationCoordinator.Reply(
                            "PAUSE. The proposed route depends on an unavailable capability. "
                                    + "Evidence: current shared register does not establish availability. "
                                    + "Alternative: verify the independent scheduler before choosing a route. "
                                    + "Resume only after the dependency is checked.",
                            BuilderConversationProtocol.EvidenceStatus.OBSERVED,
                            BuilderConversationProtocol.TurnType.INTERRUPTION,
                            BuilderConversationProtocol.Participant.USER,
                            userTurn.turnId);
                });

        BuilderConversationCoordinator.Result result = coordinator.submit(
                "interrupt-round", "Schedule the monitoring work.",
                Arrays.asList(BuilderRole.KVASIR, BuilderRole.HUGINN, BuilderRole.MARI));

        assertTrue(result.interrupted);
        assertEquals(BuilderRole.KVASIR, result.interruptedBy);
        assertEquals(BuilderConversationProtocol.TurnType.INTERRUPTION, result.interruptionType);
        assertEquals(Collections.singletonList(BuilderRole.KVASIR), invoked);
        assertEquals(Arrays.asList(BuilderRole.HUGINN, BuilderRole.MARI), result.notRunRoles);
        List<BuilderConversationProtocol.Turn> saved = journal.readThread("interrupt-round");
        assertEquals(2, saved.size());
        assertEquals(BuilderConversationProtocol.TurnType.INTERRUPTION, saved.get(1).type);
        BuilderConversationProtocol.validateThread(saved);
    }


    @Test public void improvementIncidentStopsRoundAndPersistsOpenRecord() throws Exception {
        BuilderConversationStore journal = openStore();
        JSONObject incident = new JSONObject();
        incident.put("incident_summary", "A Builder overlooked an adopted alternative.");
        incident.put("observed_behavior", "Recommended freeing a ChatGPT task slot.");
        incident.put("expected_behavior", "Check the independent scheduler first.");
        incident.put("evidence", "Project record identifies an independent scheduler.");
        incident.put("unknowns", "Deployment and GitHub access are unverified.");
        incident.put("suspected_causes", "Context delivery or reasoning failure.");
        incident.put("proposed_next_step", "Peer-review the operational context path.");
        incident.put("status", "OPEN_DIAGNOSIS");
        BuilderConversationCoordinator coordinator = new BuilderConversationCoordinator(
                journal,
                (role, context, userTurn) -> new BuilderConversationCoordinator.Reply(
                        incident.toString(), BuilderConversationProtocol.EvidenceStatus.OBSERVED,
                        BuilderConversationProtocol.TurnType.IMPROVEMENT_INCIDENT,
                        BuilderConversationProtocol.Participant.USER, userTurn.turnId));

        BuilderConversationCoordinator.Result result = coordinator.submit(
                "incident-round", "Review the scheduler recommendation.",
                Arrays.asList(BuilderRole.KVASIR, BuilderRole.BALDR));

        assertTrue(result.interrupted);
        assertEquals(BuilderConversationProtocol.TurnType.IMPROVEMENT_INCIDENT, result.interruptionType);
        assertEquals(Collections.singletonList(BuilderRole.BALDR), result.notRunRoles);
        List<BuilderConversationProtocol.Turn> saved = journal.readThread("incident-round");
        assertEquals(2, saved.size());
        assertEquals("OPEN_DIAGNOSIS", new JSONObject(saved.get(1).body).getString("status"));
        BuilderConversationProtocol.validateThread(saved);
    }

}
