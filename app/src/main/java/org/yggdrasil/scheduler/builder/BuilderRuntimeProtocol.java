package org.yggdrasil.scheduler.builder;

/** Versioned message contract between Android clients and the Builder-native process. */
public final class BuilderRuntimeProtocol {
    public static final int VERSION = 1;
    public static final int MESSAGE_GET_HEALTH = 1;
    public static final int MESSAGE_HEALTH = 101;
    public static final int MESSAGE_RESULT = 102;
    public static final int MESSAGE_ERROR = 199;
    public static final int MESSAGE_APPEND_TURN = 2;
    public static final int MESSAGE_READ_THREAD = 3;
    public static final int MESSAGE_LIST_THREADS = 4;
    public static final int MESSAGE_EXPORT_SNAPSHOT = 5;
    public static final int MESSAGE_GET_DEVICE_INVENTORY = 6;

    public static final String KEY_PROTOCOL_VERSION = "protocol_version";
    public static final String KEY_RUNTIME_ID = "runtime_id";
    public static final String KEY_CREATED_AT = "created_at_epoch_ms";
    public static final String KEY_STARTED_AT = "process_started_at_epoch_ms";
    public static final String KEY_PROCESS_NAME = "process_name";
    public static final String KEY_STATUS = "status";
    public static final String KEY_BUILDERS = "builders";
    public static final String KEY_CAPABILITIES = "capabilities";
    public static final String KEY_ERROR = "error";
    public static final String KEY_RESULT_JSON = "result_json";
    public static final String KEY_DEVICE_INVENTORY_JSON = "device_inventory_json";
    public static final String KEY_THREAD_ID = "thread_id";
    public static final String KEY_TURN_PROTOCOL = "turn_protocol";
    public static final String KEY_TURN_THREAD = "turn_thread";
    public static final String KEY_TURN_ID = "turn_id";
    public static final String KEY_TURN_SPEAKER = "turn_speaker";
    public static final String KEY_TURN_RECIPIENT = "turn_recipient";
    public static final String KEY_TURN_REPLY_TO = "turn_reply_to";
    public static final String KEY_TURN_TYPE = "turn_type";
    public static final String KEY_TURN_EVIDENCE = "turn_evidence";
    public static final String KEY_TURN_TIMESTAMP = "turn_timestamp";
    public static final String KEY_TURN_BODY = "turn_body";

    public static final String PROCESS_NAME = "org.yggdrasil.builderhost:builder_runtime";

    private BuilderRuntimeProtocol() {}
}
