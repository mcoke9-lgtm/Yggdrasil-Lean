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
}
