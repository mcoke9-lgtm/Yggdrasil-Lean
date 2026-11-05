package org.yggdrasil.scheduler.builder;

import android.content.Context;
import android.database.sqlite.SQLiteDatabase;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.File;
import java.util.HashMap;
import java.util.Map;

/**
 * Restores a validated recovery envelope into two NEW, isolated SQLite databases.
 *
 * This is a staging operation only: it never replaces, renames, deletes, or activates
 * the live conversation or subject-index databases. A later activation protocol must
 * coordinate both staged files as a pair.
 */
public final class BuilderRecoveryStager {
    private BuilderRecoveryStager() { }

    public static final class StageResult {
        public final String stagingId;
        public final String conversationDatabaseName;
        public final String subjectDatabaseName;
        public final int threadCount;
        public final int turnCount;
        public final int subjectEventCount;
        public final boolean readBackVerified;
        public final String conversationDataSha256;
        public final String subjectDataSha256;

        private StageResult(String stagingId, String conversationDatabaseName,
                            String subjectDatabaseName, int threadCount, int turnCount,
                            int subjectEventCount, boolean readBackVerified,
                            String conversationDataSha256, String subjectDataSha256) {
            this.stagingId = stagingId;
            this.conversationDatabaseName = conversationDatabaseName;
            this.subjectDatabaseName = subjectDatabaseName;
            this.threadCount = threadCount;
            this.turnCount = turnCount;
            this.subjectEventCount = subjectEventCount;
            this.readBackVerified = readBackVerified;
            this.conversationDataSha256 = conversationDataSha256;
            this.subjectDataSha256 = subjectDataSha256;
        }
    }

    /**
     * Creates a fresh staged pair. Existing stage names are never overwritten, making
     * retries explicit and preventing an old or partially inspected stage being reused.
     * On any failure, only database files created by this invocation are removed.
     */
    public static StageResult stage(Context context, JSONObject recovery, String stagingId) {
        if (context == null) throw new IllegalArgumentException("context is required");
        BuilderRecoveryPackage.ValidationReport report = BuilderRecoveryPackage.validate(recovery);
        String id = requiredStagingId(stagingId);
        String conversationName = "builder-recovery-" + id + "-conversations.db";
        String subjectName = "builder-recovery-" + id + "-subjects.db";
        Context app = context.getApplicationContext();

        File conversationFile = app.getDatabasePath(conversationName);
        File subjectFile = app.getDatabasePath(subjectName);
        if (conversationFile.exists() || subjectFile.exists())
            throw new IllegalStateException("A staging database already exists for " + id);

        BuilderConversationStore conversations = null;
        BuilderSubjectThreadIndex subjects = null;
        boolean success = false;
        try {
            conversations = new BuilderConversationStore(app, conversationName);
            subjects = new BuilderSubjectThreadIndex(app, subjectName);
            // Initialize both databases even when one snapshot is empty.
            conversations.getWritableDatabase();
            subjects.getWritableDatabase();

            JSONObject conversationSnapshot = recovery.getJSONObject("conversation_snapshot");
            JSONArray threads = conversationSnapshot.getJSONArray("threads");
            for (int i = 0; i < threads.length(); i++) {
                JSONArray turns = threads.getJSONObject(i).getJSONArray("turns");
                for (int j = 0; j < turns.length(); j++)
                    conversations.append(readTurn(turns.getJSONObject(j)));
            }

            JSONObject subjectSnapshot = recovery.getJSONObject("subject_thread_snapshot");
            JSONArray eventRows = subjectSnapshot.getJSONArray("events");
            java.util.List<BuilderSubjectThreadIndex.Event> events = new java.util.ArrayList<>();
            for (int i = 0; i < eventRows.length(); i++)
                events.add(readEvent(eventRows.getJSONObject(i)));
            if (!events.isEmpty()) subjects.appendAll(events);

            verifyConversationReadBack(conversations, threads);
            if (!subjects.exportSnapshot().getJSONArray("events").toString().equals(eventRows.toString()))
                throw new IllegalStateException("Staged subject metadata did not match recovery snapshot");

            String conversationDigest = sha256(threads.toString());
            String subjectDigest = sha256(eventRows.toString());
            success = true;
            return new StageResult(id, conversationName, subjectName, report.threadCount,
                    report.turnCount, report.subjectEventCount, true, conversationDigest, subjectDigest);
        } catch (org.json.JSONException e) {
            throw new IllegalArgumentException("Malformed recovery snapshot during staging", e);
        } finally {
            if (subjects != null) subjects.close();
            if (conversations != null) conversations.close();
            if (!success) {
                app.deleteDatabase(conversationName);
                app.deleteDatabase(subjectName);
            }
        }
    }

    private static void verifyConversationReadBack(BuilderConversationStore store, JSONArray source)
            throws org.json.JSONException {
        Map<String, JSONArray> expected = new HashMap<>();
        for (int i = 0; i < source.length(); i++) {
            JSONObject thread = source.getJSONObject(i);
            expected.put(thread.getString("thread_id"), thread.getJSONArray("turns"));
        }
        if (store.listThreadIds().size() != expected.size())
            throw new IllegalStateException("Staged conversation thread count did not match");
        for (Map.Entry<String, JSONArray> entry : expected.entrySet()) {
            java.util.List<BuilderConversationProtocol.Turn> actual = store.readThread(entry.getKey());
            JSONArray expectedTurns = entry.getValue();
            if (actual.size() != expectedTurns.length())
                throw new IllegalStateException("Staged turn count did not match for " + entry.getKey());
            for (int i = 0; i < actual.size(); i++) {
                BuilderConversationProtocol.Turn turn = actual.get(i);
                JSONObject row = expectedTurns.getJSONObject(i);
                if (!turn.turnId.equals(row.getString("turn_id"))
                        || !turn.threadId.equals(row.getString("thread_id"))
                        || turn.protocolVersion != row.getInt("protocol_version")
                        || !turn.speaker.name().equals(row.getString("speaker"))
                        || !nullableEquals(turn.recipient == null ? null : turn.recipient.name(), row, "recipient")
                        || !nullableEquals(turn.replyToTurnId, row, "reply_to_turn_id")
                        || !turn.type.name().equals(row.getString("turn_type"))
                        || !turn.evidenceStatus.name().equals(row.getString("evidence_status"))
                        || turn.timestampEpochMillis != row.getLong("timestamp_epoch_millis")
                        || !turn.body.equals(row.getString("body")))
                    throw new IllegalStateException("Staged conversation content did not match for " + entry.getKey());
            }
        }
    }

    private static boolean nullableEquals(String actual, JSONObject row, String key)
            throws org.json.JSONException {
        Object value = row.get(key);
        return actual == null ? value == JSONObject.NULL : actual.equals(value);
    }

    private static BuilderConversationProtocol.Turn readTurn(JSONObject row)
            throws org.json.JSONException {
        Object recipientValue = row.get("recipient");
        Object replyValue = row.get("reply_to_turn_id");
        String recipient = recipientValue == JSONObject.NULL ? null : (String) recipientValue;
        String replyTo = replyValue == JSONObject.NULL ? null : (String) replyValue;
        return new BuilderConversationProtocol.Turn(
                row.getInt("protocol_version"), row.getString("thread_id"), row.getString("turn_id"),
                BuilderConversationProtocol.Participant.valueOf(row.getString("speaker")),
                recipient == null ? null : BuilderConversationProtocol.Participant.valueOf(recipient),
                replyTo, BuilderConversationProtocol.TurnType.valueOf(row.getString("turn_type")),
                BuilderConversationProtocol.EvidenceStatus.valueOf(row.getString("evidence_status")),
                row.getLong("timestamp_epoch_millis"), row.getString("body"));
    }

    private static BuilderSubjectThreadIndex.Event readEvent(JSONObject row)
            throws org.json.JSONException {
        Object subheadingValue = row.get("subheading_id");
        Object participantValue = row.get("participant");
        String subheading = subheadingValue == JSONObject.NULL ? null : (String) subheadingValue;
        String participant = participantValue == JSONObject.NULL ? null : (String) participantValue;
        if (participant != null) BuilderRole.valueOf(participant);
        return new BuilderSubjectThreadIndex.Event(row.getString("event_id"),
                row.getString("thread_id"), row.getString("subject_id"),
                BuilderSubjectThreadIndex.EventType.valueOf(row.getString("event_type")),
                subheading, participant, row.getLong("timestamp_epoch_millis"));
    }

    private static String sha256(String value) {
        try {
            byte[] digest = java.security.MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder out = new StringBuilder();
            for (byte b : digest) out.append(String.format(java.util.Locale.ROOT, "%02x", b & 0xff));
            return out.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }

    private static String requiredStagingId(String value) {
        if (value == null || !value.matches("[A-Za-z0-9_-]{1,48}"))
            throw new IllegalArgumentException("stagingId must contain 1-48 letters, digits, '_' or '-'");
        return value;
    }
}
