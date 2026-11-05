package org.yggdrasil.scheduler.builder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.UUID;

/** Privacy-minimized metadata envelope for an explicitly user-directed app handoff. */
public final class BuilderBridgeEnvelope {
    public static final String SCHEMA = "yggdrasil.chatgpt-bridge-event";
    public static final int VERSION = 1;
    private BuilderBridgeEnvelope() {}
    public static org.json.JSONObject create(String direction, String sourceSystem,
            String destinationSystem, String threadId, String conversationTurnId,
            String content, String consent, String state, String relatedEventId,
            long timestampEpochMillis) {
        require(direction,"direction"); require(sourceSystem,"sourceSystem");
        require(destinationSystem,"destinationSystem"); require(threadId,"threadId");
        require(content,"content"); require(consent,"consent"); require(state,"state");
        if (content.length() > BuilderConversationProtocol.MAX_BODY_LENGTH)
            throw new IllegalArgumentException("Bridge content exceeds 8000 characters");
        if (timestampEpochMillis <= 0) throw new IllegalArgumentException("timestamp must be positive");
        try {
            org.json.JSONObject event = new org.json.JSONObject();
            event.put("schema",SCHEMA); event.put("schema_version",VERSION);
            event.put("event_id",UUID.randomUUID().toString()); event.put("direction",direction);
            event.put("source_system",sourceSystem); event.put("destination_system",destinationSystem);
            event.put("thread_id",threadId);
            event.put("conversation_turn_id",conversationTurnId == null ? org.json.JSONObject.NULL : conversationTurnId);
            event.put("created_at_epoch_ms",timestampEpochMillis);
            event.put("content_sha256",sha256(content)); event.put("content_char_count",content.length());
            event.put("consent",consent); event.put("state",state);
            event.put("related_event_id",relatedEventId == null ? org.json.JSONObject.NULL : relatedEventId);
            event.put("source_identity_verified",false); event.put("content_body_stored_in_event",false);
            return event;
        } catch (org.json.JSONException e) { throw new IllegalStateException("Could not encode bridge envelope",e); }
    }
    public static String sha256(String value) {
        if (value == null) throw new IllegalArgumentException("value is required");
        try {
            byte[] digest=MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder out=new StringBuilder();
            for(byte b:digest) out.append(String.format(java.util.Locale.ROOT,"%02x",b&0xff));
            return out.toString();
        } catch(Exception e) { throw new IllegalStateException("SHA-256 unavailable",e); }
    }
    private static void require(String v,String field) {
        if(v==null||v.trim().isEmpty()) throw new IllegalArgumentException(field+" must not be blank");
    }
}
