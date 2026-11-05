package org.yggdrasil.scheduler.builder;

/** Versioned message contract between Android clients and the Builder-native process. */
public final class BuilderRuntimeProtocol {
    public static final int VERSION = 1;
    public static final int MESSAGE_GET_HEALTH = 1;
    public static final int MESSAGE_HEALTH = 101;
    public static final int MESSAGE_ERROR = 199;

    public static final String KEY_PROTOCOL_VERSION = "protocol_version";
    public static final String KEY_RUNTIME_ID = "runtime_id";
    public static final String KEY_CREATED_AT = "created_at_epoch_ms";
    public static final String KEY_STARTED_AT = "process_started_at_epoch_ms";
    public static final String KEY_PROCESS_NAME = "process_name";
    public static final String KEY_STATUS = "status";
    public static final String KEY_BUILDERS = "builders";
    public static final String KEY_CAPABILITIES = "capabilities";
    public static final String KEY_ERROR = "error";

    public static final String PROCESS_NAME = "org.yggdrasil.builderhost:builder_runtime";

    private BuilderRuntimeProtocol() {}
}
