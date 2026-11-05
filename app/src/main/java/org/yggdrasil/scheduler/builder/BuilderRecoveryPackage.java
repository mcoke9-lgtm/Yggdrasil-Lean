package org.yggdrasil.scheduler.builder;

import org.json.JSONArray;
import org.json.JSONObject;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Builds and validates a recovery envelope containing two distinct snapshots:
 * canonical conversation turns and subject-thread metadata. This class never
 * mutates either store; validation is a read-only preflight for a later restore.
 */
public final class BuilderRecoveryPackage {
    public static final String FORMAT = "yggdrasil-nine-builder-recovery";
    public static final int FORMAT_VERSION = 1;

    private BuilderRecoveryPackage() { }

    public static final class ValidationReport {
        public final int threadCount;
        public final int turnCount;
        public final int subjectEventCount;
        public final List<String> orphanSubjectThreadIds;

        private ValidationReport(int threadCount, int turnCount, int subjectEventCount,
                                 List<String> orphanSubjectThreadIds) {
            this.threadCount = threadCount;
            this.turnCount = turnCount;
            this.subjectEventCount = subjectEventCount;
            this.orphanSubjectThreadIds = java.util.Collections.unmodifiableList(
                    new ArrayList<>(orphanSubjectThreadIds));
        }
    }

    /** Creates an envelope; neither underlying store is modified. */
    public static JSONObject create(BuilderConversationJournal conversations,
                                    BuilderSubjectThreadIndex subjects) {
        if (conversations == null || subjects == null)
            throw new IllegalArgumentException("Both recovery stores are required");
        try {
            JSONObject root = new JSONObject();
            root.put("format", FORMAT);
            root.put("format_version", FORMAT_VERSION);
            root.put("created_at_epoch_millis", System.currentTimeMillis());
            // Keep the two histories separate; do not flatten or duplicate turns.
            root.put("conversation_snapshot", conversations.exportSnapshot());
            root.put("subject_thread_snapshot", subjects.exportSnapshot());
            root.put("content_sha256", checksum(root));
            return root;
        } catch (Exception e) {
            if (e instanceof IllegalArgumentException) throw (IllegalArgumentException) e;
            throw new IllegalStateException("Could not create recovery package", e);
        }
    }

    /**
     * Validates package and both nested snapshots without writing to either store.
     * Throws IllegalArgumentException on any structural, checksum, identity, or
     * conversation-protocol failure. Orphan metadata thread IDs are reported, not
     * rejected: a routing decision can be recorded before a conversation round
     * subsequently fails to append its first user turn.
     */
    public static ValidationReport validate(JSONObject root) {
        if (root == null) throw new IllegalArgumentException("Recovery package is required");
        try {
            if (!FORMAT.equals(root.getString("format")))
                throw new IllegalArgumentException("Unsupported recovery package format");
            if (root.getInt("format_version") != FORMAT_VERSION)
                throw new IllegalArgumentException("Unsupported recovery package version");
            verifyChecksum(root, "recovery package");

            JSONObject conversation = root.getJSONObject("conversation_snapshot");
            JSONObject subject = root.getJSONObject("subject_thread_snapshot");
            int[] counts = validateConversationSnapshot(conversation);
            Set<String> conversationThreadIds = conversationThreadIds(conversation);
            int eventCount = validateSubjectSnapshot(subject);

            Set<String> subjectThreadIds = new HashSet<>();
            JSONArray events = subject.getJSONArray("events");
            for (int i = 0; i < events.length(); i++)
                subjectThreadIds.add(events.getJSONObject(i).getString("thread_id"));
            List<String> orphans = new ArrayList<>();
            for (String id : subjectThreadIds)
                if (!conversationThreadIds.contains(id)) orphans.add(id);
            java.util.Collections.sort(orphans);
            return new ValidationReport(counts[0], counts[1], eventCount, orphans);
        } catch (org.json.JSONException e) {
            throw new IllegalArgumentException("Malformed recovery package", e);
        }
    }

    private static int[] validateConversationSnapshot(JSONObject snapshot)
            throws org.json.JSONException {
        if (!"yggdrasil-nine-builder-conversations".equals(snapshot.getString("format"))
                || snapshot.getInt("format_version") != 1)
            throw new IllegalArgumentException("Unsupported conversation snapshot");
        verifyChecksum(snapshot, "conversation snapshot");
        JSONArray threads = snapshot.getJSONArray("threads");
        if (snapshot.getInt("thread_count") != threads.length())
            throw new IllegalArgumentException("Conversation thread count does not match");
        Set<String> threadIds = new HashSet<>();
        int turnsTotal = 0;
        for (int i = 0; i < threads.length(); i++) {
            JSONObject thread = threads.getJSONObject(i);
            String threadId = required(thread.getString("thread_id"), "thread_id");
            if (!threadIds.add(threadId))
                throw new IllegalArgumentException("Duplicate conversation thread: " + threadId);
            JSONArray rows = thread.getJSONArray("turns");
            List<BuilderConversationProtocol.Turn> turns = new ArrayList<>();
            for (int j = 0; j < rows.length(); j++) {
                JSONObject row = rows.getJSONObject(j);
                String rowThread = row.getString("thread_id");
                if (!threadId.equals(rowThread))
                    throw new IllegalArgumentException("Turn belongs to a different thread");
                Object recipientValue = row.get("recipient");
                Object replyValue = row.get("reply_to_turn_id");
                String recipient = recipientValue == JSONObject.NULL ? null : (String) recipientValue;
                String replyTo = replyValue == JSONObject.NULL ? null : (String) replyValue;
                turns.add(new BuilderConversationProtocol.Turn(
                        row.getInt("protocol_version"), rowThread, row.getString("turn_id"),
                        BuilderConversationProtocol.Participant.valueOf(row.getString("speaker")),
                        recipient == null ? null : BuilderConversationProtocol.Participant.valueOf(recipient),
                        replyTo,
                        BuilderConversationProtocol.TurnType.valueOf(row.getString("turn_type")),
                        BuilderConversationProtocol.EvidenceStatus.valueOf(row.getString("evidence_status")),
                        row.getLong("timestamp_epoch_millis"), row.getString("body")));
            }
            BuilderConversationProtocol.validateThread(turns);
            turnsTotal += turns.size();
        }
        if (snapshot.getInt("turn_count") != turnsTotal)
            throw new IllegalArgumentException("Conversation turn count does not match");
        return new int[]{threads.length(), turnsTotal};
    }

    private static Set<String> conversationThreadIds(JSONObject snapshot)
            throws org.json.JSONException {
        Set<String> ids = new HashSet<>();
        JSONArray threads = snapshot.getJSONArray("threads");
        for (int i = 0; i < threads.length(); i++)
            ids.add(threads.getJSONObject(i).getString("thread_id"));
        return ids;
    }

    private static int validateSubjectSnapshot(JSONObject snapshot)
            throws org.json.JSONException {
        if (!"yggdrasil-subject-thread-index".equals(snapshot.getString("format"))
                || snapshot.getInt("format_version") != 1)
            throw new IllegalArgumentException("Unsupported subject-thread snapshot");
        verifyChecksum(snapshot, "subject-thread snapshot");
        JSONArray rows = snapshot.getJSONArray("events");
        if (snapshot.getInt("event_count") != rows.length())
            throw new IllegalArgumentException("Subject event count does not match");
        Set<String> eventIds = new HashSet<>();
        for (int i = 0; i < rows.length(); i++) {
            JSONObject row = rows.getJSONObject(i);
            String eventId = required(row.getString("event_id"), "event_id");
            if (!eventIds.add(eventId))
                throw new IllegalArgumentException("Duplicate subject event ID: " + eventId);
            Object subheadingValue = row.get("subheading_id");
            Object participantValue = row.get("participant");
            String subheading = subheadingValue == JSONObject.NULL ? null : (String) subheadingValue;
            String participant = participantValue == JSONObject.NULL ? null : (String) participantValue;
            BuilderSubjectThreadIndex.Event event = new BuilderSubjectThreadIndex.Event(
                    eventId, row.getString("thread_id"), row.getString("subject_id"),
                    BuilderSubjectThreadIndex.EventType.valueOf(row.getString("event_type")),
                    subheading, participant, row.getLong("timestamp_epoch_millis"));
            if (participant != null) BuilderRole.valueOf(participant);
        }
        return rows.length();
    }

    private static void verifyChecksum(JSONObject snapshot, String label)
            throws org.json.JSONException {
        String claimed = snapshot.getString("content_sha256");
        if (!claimed.matches("[0-9a-f]{64}"))
            throw new IllegalArgumentException("Invalid checksum format in " + label);
        JSONObject content = new JSONObject(snapshot.toString());
        content.remove("content_sha256");
        if (!claimed.equals(checksum(content)))
            throw new IllegalArgumentException("Checksum mismatch in " + label);
    }

    private static String checksum(JSONObject content) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(content.toString().getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (byte b : digest)
                hex.append(String.format(java.util.Locale.ROOT, "%02x", b & 0xff));
            return hex.toString();
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }

    private static String required(String value, String field) {
        if (value == null || value.trim().isEmpty())
            throw new IllegalArgumentException(field + " must not be blank");
        return value.trim();
    }
}
