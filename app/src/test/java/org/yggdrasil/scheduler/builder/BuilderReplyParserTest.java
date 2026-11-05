package org.yggdrasil.scheduler.builder;

import org.junit.Test;
import org.json.JSONObject;

import static org.junit.Assert.*;

public class BuilderReplyParserTest {
    @Test public void parsesBuilderToBuilderQuestion() {
        String json = "{"
                + "\"body\":\"Kvasir, verify the source.\","
                + "\"turn_type\":\"QUESTION\","
                + "\"recipient\":\"KVASIR\","
                + "\"reply_to_turn_id\":null,"
                + "\"evidence_status\":\"PROPOSED\"}";
        BuilderConversationCoordinator.Reply reply = BuilderReplyParser.parse(json);
        assertEquals(BuilderConversationProtocol.TurnType.QUESTION, reply.type);
        assertEquals(BuilderConversationProtocol.Participant.KVASIR, reply.recipient);
        assertNull(reply.replyToTurnId);
        assertEquals(BuilderConversationProtocol.EvidenceStatus.PROPOSED, reply.evidenceStatus);
    }

    @Test public void parsesPeerResponseWithReference() {
        String json = "{"
                + "\"body\":\"The cited record supports the claim.\","
                + "\"turn_type\":\"RESPONSE\","
                + "\"recipient\":\"HUGINN\","
                + "\"reply_to_turn_id\":\"question-1\","
                + "\"evidence_status\":\"OBSERVED\"}";
        BuilderConversationCoordinator.Reply reply = BuilderReplyParser.parse(json);
        assertEquals(BuilderConversationProtocol.TurnType.RESPONSE, reply.type);
        assertEquals(BuilderConversationProtocol.Participant.HUGINN, reply.recipient);
        assertEquals("question-1", reply.replyToTurnId);
    }

    @Test public void rejectsDecisionEvenWhenJsonIsOtherwiseValid() {
        String json = "{"
                + "\"body\":\"Approved.\","
                + "\"turn_type\":\"DECISION\","
                + "\"recipient\":\"USER\","
                + "\"reply_to_turn_id\":null,"
                + "\"evidence_status\":\"PROPOSED\"}";
        assertThrows(IllegalArgumentException.class, () -> BuilderReplyParser.parse(json));
    }

    @Test public void rejectsUnknownRecipient() {
        String json = "{"
                + "\"body\":\"Ask someone.\","
                + "\"turn_type\":\"QUESTION\","
                + "\"recipient\":\"UNKNOWN_AGENT\","
                + "\"reply_to_turn_id\":null,"
                + "\"evidence_status\":\"PROPOSED\"}";
        assertThrows(IllegalArgumentException.class, () -> BuilderReplyParser.parse(json));
    }

    @Test public void rejectsMissingRequiredFieldsAndMalformedJson() {
        assertThrows(IllegalArgumentException.class,
                () -> BuilderReplyParser.parse("{\"body\":\"Missing fields\"}"));
        assertThrows(IllegalArgumentException.class,
                () -> BuilderReplyParser.parse("{not-json"));
        assertThrows(IllegalArgumentException.class,
                () -> BuilderReplyParser.parse("  "));
    }

    @Test public void rejectsBlankBodyAndUnsupportedEvidenceStatus() {
        String blankBody = "{"
                + "\"body\":\"  \","
                + "\"turn_type\":\"CONTRIBUTION\","
                + "\"recipient\":null,"
                + "\"reply_to_turn_id\":null,"
                + "\"evidence_status\":\"UNKNOWN\"}";
        String badEvidence = "{"
                + "\"body\":\"A claim.\","
                + "\"turn_type\":\"CONTRIBUTION\","
                + "\"recipient\":null,"
                + "\"reply_to_turn_id\":null,"
                + "\"evidence_status\":\"CERTAIN\"}";
        assertThrows(IllegalArgumentException.class, () -> BuilderReplyParser.parse(blankBody));
        assertThrows(IllegalArgumentException.class, () -> BuilderReplyParser.parse(badEvidence));
    }


    @Test public void parsesStructuredImprovementIncidentAndKeepsItOpen() throws Exception {
        JSONObject incident = new JSONObject();
        incident.put("incident_summary", "A Builder overlooked an adopted alternative.");
        incident.put("observed_behavior", "Recommended freeing a ChatGPT task slot.");
        incident.put("expected_behavior", "Check the independent scheduler first.");
        incident.put("evidence", "Project record identifies an independent scheduler.");
        incident.put("unknowns", "Current deployment and GitHub access are unverified.");
        incident.put("suspected_causes", "Context delivery or reasoning failure.");
        incident.put("proposed_next_step", "Peer-review the operational context path.");
        incident.put("status", "OPEN_DIAGNOSIS");
        JSONObject response = new JSONObject();
        response.put("body", incident.toString());
        response.put("turn_type", "IMPROVEMENT_INCIDENT");
        response.put("recipient", "USER");
        response.put("reply_to_turn_id", "trigger-1");
        response.put("evidence_status", "OBSERVED");
        BuilderConversationCoordinator.Reply reply = BuilderReplyParser.parse(response.toString());
        assertEquals(BuilderConversationProtocol.TurnType.IMPROVEMENT_INCIDENT, reply.type);
        assertEquals(BuilderConversationProtocol.Participant.USER, reply.recipient);
        assertTrue(reply.body.contains("OPEN_DIAGNOSIS"));
    }

    @Test public void rejectsImprovementIncidentThatClaimsAdoptionAtCreation() throws Exception {
        JSONObject incident = new JSONObject();
        incident.put("incident_summary", "A failure occurred.");
        incident.put("observed_behavior", "Observed.");
        incident.put("expected_behavior", "Expected.");
        incident.put("evidence", "Evidence.");
        incident.put("unknowns", "Unknown.");
        incident.put("suspected_causes", "Cause.");
        incident.put("proposed_next_step", "Review.");
        incident.put("status", "ADOPTED");
        JSONObject response = new JSONObject();
        response.put("body", incident.toString());
        response.put("turn_type", "IMPROVEMENT_INCIDENT");
        response.put("recipient", "USER");
        response.put("reply_to_turn_id", "trigger-1");
        response.put("evidence_status", "OBSERVED");
        assertThrows(IllegalArgumentException.class, () -> BuilderReplyParser.parse(response.toString()));
    }

    @Test public void parsesStructuredInterruptionWithExplicitResumeCondition() throws Exception {
        JSONObject body = new JSONObject();
        body.put("stop_state", "PAUSE");
        body.put("observed_constraint", "Scheduler availability is unknown.");
        body.put("source_time", "Shared operational snapshot observed 2026-11-05.");
        body.put("affected_step", "Creating a daily PR monitor.");
        body.put("impact", "The current route could claim a task is scheduled without proof.");
        body.put("unknowns", "Deployment and GitHub access are not verified.");
        body.put("feasible_alternative", "Verify the independent scheduler service and repository access.");
        body.put("required_recheck_authorization", "Recheck service and permissions; request authorization before deployment or credential changes.");
        body.put("resume_condition", "Resume after an actual monitor run is confirmed.");
        JSONObject response = new JSONObject();
        response.put("body", body.toString());
        response.put("turn_type", "INTERRUPTION");
        response.put("recipient", "USER");
        response.put("reply_to_turn_id", "trigger-1");
        response.put("evidence_status", "OBSERVED");
        BuilderConversationCoordinator.Reply reply = BuilderReplyParser.parse(response.toString());
        assertEquals(BuilderConversationProtocol.TurnType.INTERRUPTION, reply.type);
        assertTrue(reply.body.contains("resume_condition"));
    }

    @Test public void parsesStructuredCorrectionPlan() throws Exception {
        JSONObject body = new JSONObject();
        body.put("correction_id", "corr-1");
        body.put("incident_turn_id", "incident-1");
        body.put("objective", "Correct the unsuitable method.");
        body.put("acceptance_criteria", "The replacement method is checked.");
        body.put("steps", "Inspect, implement, test, verify.");
        body.put("owner", "Hodr");
        body.put("rollback_condition", "Stop if the test fails.");
        body.put("status", "PROPOSED");
        JSONObject response = new JSONObject();
        response.put("body", body.toString());
        response.put("turn_type", "CORRECTION_PLAN");
        response.put("recipient", "USER");
        response.put("reply_to_turn_id", "incident-1");
        response.put("evidence_status", "PROPOSED");
        BuilderConversationCoordinator.Reply reply = BuilderReplyParser.parse(response.toString());
        assertEquals(BuilderConversationProtocol.TurnType.CORRECTION_PLAN, reply.type);
        assertEquals("incident-1", reply.replyToTurnId);
    }

    @Test public void parsesImplementedCorrectionUpdateAndRejectsBuilderAdoption() throws Exception {
        JSONObject body = new JSONObject();
        body.put("correction_id", "corr-1");
        body.put("incident_turn_id", "incident-1");
        body.put("stage", "IMPLEMENTED");
        body.put("evidence", "A source commit contains the correction implementation.");
        body.put("artifact_reference", "commit:abc123");
        body.put("acceptance_criteria_result", "The implementation matches the first acceptance criterion.");
        body.put("unresolved", "Tests remain pending.");
        JSONObject response = new JSONObject();
        response.put("body", body.toString());
        response.put("turn_type", "CORRECTION_UPDATE");
        response.put("recipient", "USER");
        response.put("reply_to_turn_id", "plan-1");
        response.put("evidence_status", "IMPLEMENTED");
        assertEquals(BuilderConversationProtocol.EvidenceStatus.IMPLEMENTED,
                BuilderReplyParser.parse(response.toString()).evidenceStatus);

        body.put("stage", "ADOPTED");
        response.put("body", body.toString());
        response.put("evidence_status", "ADOPTED");
        assertThrows(IllegalArgumentException.class,
                () -> BuilderReplyParser.parse(response.toString()));
    }

    @Test public void rejectsUnstructuredInterruption() throws Exception {
        JSONObject response = new JSONObject();
        response.put("body", "Stop because something might be wrong.");
        response.put("turn_type", "INTERRUPTION");
        response.put("recipient", "USER");
        response.put("reply_to_turn_id", "trigger-1");
        response.put("evidence_status", "UNKNOWN");
        assertThrows(IllegalArgumentException.class, () -> BuilderReplyParser.parse(response.toString()));
    }

}
