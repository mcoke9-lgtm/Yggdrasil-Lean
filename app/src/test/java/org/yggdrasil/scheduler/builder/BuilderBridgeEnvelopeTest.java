package org.yggdrasil.scheduler.builder;
import static org.junit.Assert.*;
import org.junit.Test;
public final class BuilderBridgeEnvelopeTest {
    @Test public void sha256UsesUtf8AndLowercaseHex() {
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            BuilderBridgeEnvelope.sha256("abc"));
    }
    @Test public void envelopeStoresDigestAndConsentButNotMessageBody() throws Exception {
        String body="A private handoff";
        org.json.JSONObject event=BuilderBridgeEnvelope.create("OUTBOUND","yggdrasil","android_share_sheet",
            "thread-1",null,body,"explicit_user_approval","prepared",null,1234L);
        assertEquals(BuilderBridgeEnvelope.SCHEMA,event.getString("schema"));
        assertEquals(BuilderBridgeEnvelope.sha256(body),event.getString("content_sha256"));
        assertEquals(body.length(),event.getInt("content_char_count"));
        assertEquals("explicit_user_approval",event.getString("consent"));
        assertFalse(event.getBoolean("source_identity_verified"));
        assertFalse(event.getBoolean("content_body_stored_in_event"));
        assertFalse(event.has("content")); assertFalse(event.has("body"));
    }
    @Test public void envelopeRejectsOversizedContent() {
        StringBuilder value=new StringBuilder();
        for(int i=0;i<BuilderConversationProtocol.MAX_BODY_LENGTH+1;i++) value.append('x');
        try {
            BuilderBridgeEnvelope.create("INBOUND","external","yggdrasil","thread-1","turn-1",
                value.toString(),"explicit_user_approval","pending",null,1234L);
            fail("Expected size limit rejection");
        } catch(IllegalArgumentException expected) { assertTrue(expected.getMessage().contains("8000")); }
    }
}
