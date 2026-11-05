package org.yggdrasil.scheduler.builder;

import android.content.Context;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

import java.util.List;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
public class BuilderCorrectionLedgerTest {
    private static final String DATABASE = "builder-correction-ledger-test.db";
    private BuilderConversationStore store;

    private BuilderConversationStore openStore() {
        store = new BuilderConversationStore(RuntimeEnvironment.getApplication(), DATABASE);
        return store;
    }

    @After public void cleanUp() {
        if (store != null) store.close();
        RuntimeEnvironment.getApplication().deleteDatabase(DATABASE);
    }

    private BuilderConversationProtocol.Turn turn(
            String id, BuilderConversationProtocol.Participant speaker,
            BuilderConversationProtocol.Participant recipient, String replyTo,
            BuilderConversationProtocol.TurnType type,
            BuilderConversationProtocol.EvidenceStatus evidence, long timestamp, String body) {
        return new BuilderConversationProtocol.Turn(
                BuilderConversationProtocol.CURRENT_VERSION, "correction-thread", id,
                speaker, recipient, replyTo, type, evidence, timestamp, body);
    }

    private String planBody() throws Exception {
        JSONObject body = new JSONObject();
        body.put("correction_id", "corr-001");
        body.put("incident_turn_id", "incident-1");
        body.put("objective", "Use the project scheduler before considering provider task capacity.");
        body.put("acceptance_criteria", "A live scheduler capability check and a successful monitor run are recorded.");
        body.put("steps", "Inspect scheduler deployment, verify repository access, then run a bounded monitor.");
        body.put("owner", "Kvasir coordinates; Höðr implements; Baldr verifies.");
        body.put("rollback_condition", "Stop if access, deployment, or authorization is not established.");
        body.put("status", "PROPOSED");
        return body.toString();
    }

    private String updateBody(String stage) throws Exception {
        JSONObject body = new JSONObject();
        body.put("correction_id", "corr-001");
        body.put("incident_turn_id", "incident-1");
        body.put("stage", stage);
        body.put("evidence", "Evidence for the " + stage + " milestone is attached to the referenced artifact.");
        body.put("artifact_reference", "commit-or-run-reference-" + stage.toLowerCase());
        body.put("acceptance_criteria_result", "Acceptance criteria review for " + stage + " is recorded.");
        body.put("unresolved", "Scheduler live deployment remains a separately tracked constraint.");
        return body.toString();
    }

    private void seedIncident(BuilderConversationStore journal) throws Exception {
        journal.append(turn("origin", BuilderConversationProtocol.Participant.USER, null, null,
                BuilderConversationProtocol.TurnType.CONTRIBUTION,
                BuilderConversationProtocol.EvidenceStatus.UNKNOWN, 100, "Original task."));
        JSONObject incident = new JSONObject();
        incident.put("incident_summary", "A default task-capacity recommendation was unsuitable.");
        incident.put("observed_behavior", "The independent scheduler alternative was not checked.");
        incident.put("expected_behavior", "Check the project scheduler first.");
        incident.put("evidence", "The project has an independent scheduler prototype.");
        incident.put("unknowns", "Deployment and GitHub access.");
        incident.put("suspected_causes", "Context or reasoning failure.");
        incident.put("proposed_next_step", "Create and review a correction plan.");
        incident.put("status", "OPEN_REVIEW");
        journal.append(turn("incident-1", BuilderConversationProtocol.Participant.KVASIR,
                BuilderConversationProtocol.Participant.USER, "origin",
                BuilderConversationProtocol.TurnType.IMPROVEMENT_INCIDENT,
                BuilderConversationProtocol.EvidenceStatus.OBSERVED, 200, incident.toString()));
    }

    private BuilderConversationProtocol.Turn seedPlan(BuilderConversationStore journal) throws Exception {
        List<BuilderConversationProtocol.Turn> context = journal.readThread("correction-thread");
        BuilderConversationProtocol.Turn plan = turn("plan-1", BuilderConversationProtocol.Participant.HUGINN,
                BuilderConversationProtocol.Participant.USER, "incident-1",
                BuilderConversationProtocol.TurnType.CORRECTION_PLAN,
                BuilderConversationProtocol.EvidenceStatus.PROPOSED, 300, planBody());
        BuilderCorrectionLedger.validatePlan(plan, context, "incident-1");
        journal.append(plan);
        return plan;
    }

    @Test public void enforcesLifecycleAndRecordsAdoptionOnlyAfterIndependentVerification() throws Exception {
        BuilderConversationStore journal = openStore();
        seedIncident(journal);
        seedPlan(journal);

        BuilderConversationProtocol.Turn implemented = turn("implemented-1",
                BuilderConversationProtocol.Participant.HODR, BuilderConversationProtocol.Participant.USER,
                "plan-1", BuilderConversationProtocol.TurnType.CORRECTION_UPDATE,
                BuilderConversationProtocol.EvidenceStatus.IMPLEMENTED, 400, updateBody("IMPLEMENTED"));
        BuilderCorrectionLedger.validateUpdate(implemented, journal.readThread("correction-thread"));
        journal.append(implemented);

        BuilderConversationProtocol.Turn tested = turn("tested-1",
                BuilderConversationProtocol.Participant.HODR, BuilderConversationProtocol.Participant.USER,
                "implemented-1", BuilderConversationProtocol.TurnType.CORRECTION_UPDATE,
                BuilderConversationProtocol.EvidenceStatus.TESTED, 500, updateBody("TESTED"));
        BuilderCorrectionLedger.validateUpdate(tested, journal.readThread("correction-thread"));
        journal.append(tested);

        BuilderConversationProtocol.Turn verified = turn("verified-1",
                BuilderConversationProtocol.Participant.BALDR, BuilderConversationProtocol.Participant.USER,
                "tested-1", BuilderConversationProtocol.TurnType.CORRECTION_UPDATE,
                BuilderConversationProtocol.EvidenceStatus.VERIFIED, 600, updateBody("VERIFIED"));
        BuilderCorrectionLedger.validateUpdate(verified, journal.readThread("correction-thread"));
        journal.append(verified);

        assertEquals("corr-001", BuilderCorrectionLedger.latestAdoptableCorrection(
                journal.readThread("correction-thread")));
        BuilderConversationProtocol.Turn adopted = BuilderCorrectionLedger.recordUserAdoption(
                journal, "correction-thread", "corr-001");
        assertEquals(BuilderConversationProtocol.Participant.USER, adopted.speaker);
        assertEquals(BuilderConversationProtocol.EvidenceStatus.ADOPTED, adopted.evidenceStatus);
        assertEquals("verified-1", adopted.replyToTurnId);
        assertNull(BuilderCorrectionLedger.latestAdoptableCorrection(
                journal.readThread("correction-thread")));
        assertThrows(IllegalStateException.class, () -> BuilderCorrectionLedger.recordUserAdoption(
                journal, "correction-thread", "corr-001"));
        BuilderConversationProtocol.validateThread(journal.readThread("correction-thread"));
    }

    @Test public void rejectsSkippedStagesAndNonBaldrVerification() throws Exception {
        BuilderConversationStore journal = openStore();
        seedIncident(journal);
        seedPlan(journal);

        BuilderConversationProtocol.Turn skipped = turn("skip-1",
                BuilderConversationProtocol.Participant.HODR, BuilderConversationProtocol.Participant.USER,
                "plan-1", BuilderConversationProtocol.TurnType.CORRECTION_UPDATE,
                BuilderConversationProtocol.EvidenceStatus.TESTED, 400, updateBody("TESTED"));
        assertThrows(IllegalArgumentException.class, () ->
                BuilderCorrectionLedger.validateUpdate(skipped, journal.readThread("correction-thread")));

        BuilderConversationProtocol.Turn implemented = turn("implemented-1",
                BuilderConversationProtocol.Participant.HODR, BuilderConversationProtocol.Participant.USER,
                "plan-1", BuilderConversationProtocol.TurnType.CORRECTION_UPDATE,
                BuilderConversationProtocol.EvidenceStatus.IMPLEMENTED, 400, updateBody("IMPLEMENTED"));
        BuilderCorrectionLedger.validateUpdate(implemented, journal.readThread("correction-thread"));
        journal.append(implemented);
        BuilderConversationProtocol.Turn tested = turn("tested-1",
                BuilderConversationProtocol.Participant.HODR, BuilderConversationProtocol.Participant.USER,
                "implemented-1", BuilderConversationProtocol.TurnType.CORRECTION_UPDATE,
                BuilderConversationProtocol.EvidenceStatus.TESTED, 500, updateBody("TESTED"));
        BuilderCorrectionLedger.validateUpdate(tested, journal.readThread("correction-thread"));
        journal.append(tested);

        BuilderConversationProtocol.Turn falseVerifier = turn("verified-1",
                BuilderConversationProtocol.Participant.HODR, BuilderConversationProtocol.Participant.USER,
                "tested-1", BuilderConversationProtocol.TurnType.CORRECTION_UPDATE,
                BuilderConversationProtocol.EvidenceStatus.VERIFIED, 600, updateBody("VERIFIED"));
        assertThrows(IllegalArgumentException.class, () ->
                BuilderCorrectionLedger.validateUpdate(falseVerifier, journal.readThread("correction-thread")));
    }

    @Test public void refusesAdoptionBeforeVerifiedMilestone() throws Exception {
        BuilderConversationStore journal = openStore();
        seedIncident(journal);
        seedPlan(journal);
        assertThrows(IllegalStateException.class, () ->
                BuilderCorrectionLedger.recordUserAdoption(journal, "correction-thread", "corr-001"));
    }
}
