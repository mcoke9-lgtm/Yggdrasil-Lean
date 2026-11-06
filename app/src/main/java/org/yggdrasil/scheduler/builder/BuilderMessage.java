package org.yggdrasil.scheduler.builder;

/**
 * Immutable, transport-neutral message envelope.
 * Payload is opaque text at this layer; adapters validate their own payload schema.
 */
public final class BuilderMessage {
    public static final int CURRENT_SCHEMA_VERSION = 1;

    private final int schemaVersion;
    private final String messageId;
    private final String sender;
    private final String recipient;
    private final long timestampEpochMillis;
    private final String correlationId;
    private final String contentType;
    private final String payload;

    public BuilderMessage(int schemaVersion, String messageId, String sender,
                          String recipient, long timestampEpochMillis,
                          String correlationId, String contentType, String payload) {
        if (schemaVersion < 1) throw new IllegalArgumentException("schemaVersion must be positive");
        this.schemaVersion = schemaVersion;
        this.messageId = required(messageId, "messageId");
        this.sender = required(sender, "sender");
        this.recipient = required(recipient, "recipient");
        if (timestampEpochMillis <= 0) throw new IllegalArgumentException("timestamp must be positive");
        this.timestampEpochMillis = timestampEpochMillis;
        this.correlationId = required(correlationId, "correlationId");
        this.contentType = required(contentType, "contentType");
        this.payload = required(payload, "payload");
    }

    private static String required(String value, String field) {
        if (value == null || value.trim().isEmpty())
            throw new IllegalArgumentException(field + " must not be blank");
        return value;
    }

    public int getSchemaVersion() { return schemaVersion; }
    public String getMessageId() { return messageId; }
    public String getSender() { return sender; }
    public String getRecipient() { return recipient; }
    public long getTimestampEpochMillis() { return timestampEpochMillis; }
    public String getCorrelationId() { return correlationId; }
    public String getContentType() { return contentType; }
    public String getPayload() { return payload; }
}