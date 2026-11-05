package org.yggdrasil.scheduler.builder;

import java.util.List;

/** Durable append-only conversation journal contract, independent of its host process. */
public interface BuilderConversationJournal extends AutoCloseable {
    void append(BuilderConversationProtocol.Turn turn);
    List<BuilderConversationProtocol.Turn> readThread(String threadId);
    List<String> listThreadIds();
    org.json.JSONObject exportSnapshot();
    @Override void close();
}
