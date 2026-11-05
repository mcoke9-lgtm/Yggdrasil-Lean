package org.yggdrasil.scheduler.builder;

import org.junit.Test;

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

    @Test public void parsesStructuredImprovementIncidentAndKeepsItOpen() {
        String incident = "{"
                + "\\"incident_summary\\":\\"A Builder overlooked an adopted alternative.\\","
                + "\\"observed_behavior\\":\\"Recommended freeing a ChatGPT task slot.\\","
                + "\\"expected_behavior\\":\\"Check the independent scheduler first.\\","
                + "\\"evidence\\":\\"Project record identifies an independent scheduler.\\","
                + "\\"unknowns\\":\\"Current deployment and GitHub access are unverified.\\","
                + "\\"suspected_causes\\":\\"Context delivery or reasoning failure.\\","
                + "\\"proposed_next_step\\":\\"Peer-review the operational context path.\\","
                + "\\"status\\":\\"OPEN_DIAGNOSIS\\"}";
        String json = "{"
                + "\\"body\\":\"" + incident.replace("\\", "\\\\").replace("\"", "\\\"") + "\\","
                + "\\"turn_type\\":\\"IMPROVEMENT_INCIDENT\\","
                + "\\"recipient\\":\\"USER\\","
                + "\\"reply_to_turn_id\\":\\"trigger-1\\","
                + "\\"evidence_status\\":\\"OBSERVED\\"}";
        BuilderConversationCoordinator.Reply reply = BuilderReplyParser.parse(json);
        assertEquals(BuilderConversationProtocol.TurnType.IMPROVEMENT_INCIDENT, reply.type);
        assertEquals(BuilderConversationProtocol.Participant.USER, reply.recipient);
        assertTrue(reply.body.contains("OPEN_DIAGNOSIS"));
    }

    @Test public void rejectsImprovementIncidentThatClaimsAdoptionAtCreation() {
        String incident = "{"
                + "\\"incident_summary\\":\\"A failure occurred.\\","
                + "\\"observed_behavior\\":\\"Observed.\\","
                + "\\"expected_behavior\\":\\"Expected.\\","
                + "\\"evidence\\":\\"Evidence.\\","
                + "\\"unknowns\\":\\"Unknown.\\","
                + "\\"suspected_causes\\":\\"Cause.\\","
                + "\\"proposed_next_step\\":\\"Review.\\","
                + "\\"status\\":\\"ADOPTED\\"}";
        String json = "{"
                + "\\"body\\":\"" + incident.replace("\\", "\\\\").replace("\"", "\\\"") + "\\","
                + "\\"turn_type\\":\\"IMPROVEMENT_INCIDENT\\","
                + "\\"recipient\\":\\"USER\\","
                + "\\"reply_to_turn_id\\":\\"trigger-1\\","
                + "\\"evidence_status\\":\\"OBSERVED\\"}";
        assertThrows(IllegalArgumentException.class, () -> BuilderReplyParser.parse(json));
    }


    @Test public void parsesStructuredInterruptionWithExplicitResumeCondition() {
        String body = "{"
                + "\\"stop_state\\":\\"PAUSE\\","
                + "\\"observed_constraint\\":\\"Scheduler availability is unknown.\\","
                + "\\"source_time\\":\\"Shared operational snapshot observed 2026-11-05.\\","
                + "\\"affected_step\\":\\"Creating a daily PR monitor.\\","
                + "\\"impact\\":\\"The current route could claim a task is scheduled without proof.\\","
                + "\\"unknowns\\":\\"Deployment and GitHub access are not verified.\\","
                + "\\"feasible_alternative\\":\\"Verify the independent scheduler service and repository access.\\","
                + "\\"required_recheck_authorization\\":\\"Recheck service and permissions; request authorization before deployment or credential changes.\\","
                + "\\"resume_condition\\":\\"Resume after an actual monitor run is confirmed.\\"}";
        String json = "{"
                + "\\"body\\":\"" + body.replace("\\", "\\\\").replace("\"", "\\\"") + "\\","
                + "\\"turn_type\\":\\"INTERRUPTION\\","
                + "\\"recipient\\":\\"USER\\","
                + "\\"reply_to_turn_id\\":\\"trigger-1\\","
                + "\\"evidence_status\\":\\"OBSERVED\\"}";
        BuilderConversationCoordinator.Reply reply = BuilderReplyParser.parse(json);
        assertEquals(BuilderConversationProtocol.TurnType.INTERRUPTION, reply.type);
        assertTrue(reply.body.contains("resume_condition"));
    }

    @Test public void rejectsUnstructuredInterruption() {
        String json = "{"
                + "\\"body\\":\\"Stop because something might be wrong.\\","
                + "\\"turn_type\\":\\"INTERRUPTION\\","
                + "\\"recipient\\":\\"USER\\","
                + "\\"reply_to_turn_id\\":\\"trigger-1\\","
                + "\\"evidence_status\\":\\"UNKNOWN\\"}";
        assertThrows(IllegalArgumentException.class, () -> BuilderReplyParser.parse(json));
    }

}
