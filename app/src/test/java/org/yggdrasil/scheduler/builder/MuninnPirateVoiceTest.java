package org.yggdrasil.scheduler.builder;

import static org.junit.Assert.*;
import org.junit.Test;
import java.util.Collections;

public class MuninnPirateVoiceTest {
    @Test public void onlyMuninnReceivesPirateVoiceInstruction() {
        BuilderConversationProtocol.Turn user = new BuilderConversationProtocol.Turn(
                BuilderConversationProtocol.CURRENT_VERSION, "pirate-thread", "u1",
                BuilderConversationProtocol.Participant.USER, null, null,
                BuilderConversationProtocol.TurnType.CONTRIBUTION,
                BuilderConversationProtocol.EvidenceStatus.UNKNOWN, 100L, "Keep the records clear.");
        String unavailable = "{\"register_status\":\"UNAVAILABLE\","
                + "\"live_telemetry\":false,\"unlisted_capability_decision\":\"RECHECK_REQUIRED\"}";
        BuilderPromptFactory.Prompt muninn = BuilderPromptFactory.forTurn(
                BuilderRole.MUNINN, Collections.singletonList(user), user, unavailable);
        BuilderPromptFactory.Prompt huginn = BuilderPromptFactory.forTurn(
                BuilderRole.HUGINN, Collections.singletonList(user), user, unavailable);
        assertTrue(muninn.systemInstruction.contains("friendly, unmistakable pirate"));
        assertTrue(muninn.systemInstruction.contains("precise, evidence-led"));
        assertFalse(huginn.systemInstruction.contains("pirate"));
    }
}
