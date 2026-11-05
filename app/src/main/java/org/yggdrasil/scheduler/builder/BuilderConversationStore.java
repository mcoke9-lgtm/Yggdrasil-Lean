package org.yggdrasil.scheduler.builder;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Private, append-only SQLite journal for validated Nine Builder conversation turns.
 *
 * This store preserves conversation history on the device. It does not authenticate
 * remote senders, invoke models, synchronize devices, or authorize execution.
 */
final class BuilderConversationStore extends SQLiteOpenHelper implements BuilderConversationJournal {
    private static final int DATABASE_VERSION = 1;
    private static final String DEFAULT_DATABASE_NAME = "builder-conversations.db";
    private static final String TABLE = "conversation_turns";

    private static final String[] COLUMNS = {
            "_id", "thread_id", "turn_id", "protocol_version", "speaker",
            "recipient", "reply_to_turn_id", "turn_type", "evidence_status",
            "timestamp_epoch_millis", "body"
    };

    public BuilderConversationStore(Context context) {
        this(context, DEFAULT_DATABASE_NAME);
    }

    /** Alternate database names allow isolated tests without touching user data. */
    public BuilderConversationStore(Context context, String databaseName) {
        super(context.getApplicationContext(), requireDatabaseName(databaseName), null,
                DATABASE_VERSION);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE " + TABLE + " ("
                + "_id INTEGER PRIMARY KEY AUTOINCREMENT, "
                + "thread_id TEXT NOT NULL, "
                + "turn_id TEXT NOT NULL, "
                + "protocol_version INTEGER NOT NULL, "
                + "speaker TEXT NOT NULL, "
                + "recipient TEXT, "
                + "reply_to_turn_id TEXT, "
                + "turn_type TEXT NOT NULL, "
                + "evidence_status TEXT NOT NULL, "
                + "timestamp_epoch_millis INTEGER NOT NULL, "
                + "body TEXT NOT NULL, "
                + "UNIQUE(thread_id, turn_id))");
        db.execSQL("CREATE INDEX conversation_thread_order ON "
                + TABLE + "(thread_id, _id)");
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        // Never silently discard conversation history. Future schema changes need
        // an explicit, tested migration before the database version is increased.
        throw new IllegalStateException("No conversation-store migration from "
                + oldVersion + " to " + newVersion);
    }

    /**
     * Validates the complete resulting thread and atomically appends one new turn.
     * Existing turns are never updated or deleted by this API.
     */
    public synchronized void append(BuilderConversationProtocol.Turn turn) {
        BuilderConversationProtocol.validateTurn(turn);
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            List<BuilderConversationProtocol.Turn> candidate = readThread(db, turn.threadId);
            candidate.add(turn);
            BuilderConversationProtocol.validateThread(candidate);
            db.insertOrThrow(TABLE, null, toContentValues(turn));
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }

    /** Returns an immutable snapshot of a thread in append order. */
    public synchronized List<BuilderConversationProtocol.Turn> readThread(String threadId) {
        if (threadId == null || threadId.trim().isEmpty())
            throw new IllegalArgumentException("threadId must not be blank");
        return Collections.unmodifiableList(readThread(getReadableDatabase(), threadId.trim()));
    }

    /** Returns known thread IDs, most recently active first, for restart recovery/navigation. */
    public synchronized List<String> listThreadIds() {
        List<String> threadIds = new ArrayList<>();
        try (Cursor cursor = getReadableDatabase().rawQuery(
                "SELECT thread_id FROM " + TABLE
                        + " GROUP BY thread_id ORDER BY MAX(_id) DESC", null)) {
            while (cursor.moveToNext()) {
                threadIds.add(cursor.getString(0));
            }
        }
        return Collections.unmodifiableList(threadIds);
    }

    /**
     * Exports every saved conversation as a portable JSON snapshot.
     * The checksum covers the complete snapshot before the checksum field is added.
     * This is a local integrity check, not encryption or proof of origin.
     */
    public synchronized org.json.JSONObject exportSnapshot() {
        org.json.JSONObject root = new org.json.JSONObject();
        org.json.JSONArray threads = new org.json.JSONArray();
        int totalTurns = 0;
        try {
            root.put("format", "yggdrasil-nine-builder-conversations");
            root.put("format_version", 1);
            root.put("exported_at_epoch_millis", System.currentTimeMillis());
            for (String threadId : listThreadIds()) {
                org.json.JSONObject thread = new org.json.JSONObject();
                org.json.JSONArray turns = new org.json.JSONArray();
                for (BuilderConversationProtocol.Turn turn : readThread(threadId)) {
                    org.json.JSONObject item = new org.json.JSONObject();
                    item.put("protocol_version", turn.protocolVersion);
                    item.put("thread_id", turn.threadId);
                    item.put("turn_id", turn.turnId);
                    item.put("speaker", turn.speaker.name());
                    item.put("recipient", turn.recipient == null ? org.json.JSONObject.NULL : turn.recipient.name());
                    item.put("reply_to_turn_id", turn.replyToTurnId == null
                            ? org.json.JSONObject.NULL : turn.replyToTurnId);
                    item.put("turn_type", turn.type.name());
                    item.put("evidence_status", turn.evidenceStatus.name());
                    item.put("timestamp_epoch_millis", turn.timestampEpochMillis);
                    item.put("body", turn.body);
                    turns.put(item);
                    totalTurns++;
                }
                thread.put("thread_id", threadId);
                thread.put("turns", turns);
                threads.put(thread);
            }
            root.put("thread_count", threads.length());
            root.put("turn_count", totalTurns);
            root.put("threads", threads);
            root.put("content_sha256", sha256(root.toString()));
            return root;
        } catch (org.json.JSONException e) {
            throw new IllegalStateException("Could not encode conversation snapshot", e);
        }
    }

    private static String sha256(String value) {
        try {
            byte[] digest = java.security.MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (byte b : digest) hex.append(String.format(java.util.Locale.ROOT, "%02x", b & 0xff));
            return hex.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }

    private List<BuilderConversationProtocol.Turn> readThread(SQLiteDatabase db, String threadId) {
        List<BuilderConversationProtocol.Turn> turns = new ArrayList<>();
        try (Cursor cursor = db.query(TABLE, COLUMNS, "thread_id = ?",
                new String[]{threadId}, null, null, "_id ASC")) {
            while (cursor.moveToNext()) {
                turns.add(fromCursor(cursor));
            }
        }
        return turns;
    }

    private static ContentValues toContentValues(BuilderConversationProtocol.Turn turn) {
        ContentValues values = new ContentValues();
        values.put("thread_id", turn.threadId);
        values.put("turn_id", turn.turnId);
        values.put("protocol_version", turn.protocolVersion);
        values.put("speaker", turn.speaker.name());
        if (turn.recipient == null) values.putNull("recipient");
        else values.put("recipient", turn.recipient.name());
        if (turn.replyToTurnId == null) values.putNull("reply_to_turn_id");
        else values.put("reply_to_turn_id", turn.replyToTurnId);
        values.put("turn_type", turn.type.name());
        values.put("evidence_status", turn.evidenceStatus.name());
        values.put("timestamp_epoch_millis", turn.timestampEpochMillis);
        values.put("body", turn.body);
        return values;
    }

    private static BuilderConversationProtocol.Turn fromCursor(Cursor cursor) {
        String recipient = cursor.isNull(5) ? null : cursor.getString(5);
        String replyTo = cursor.isNull(6) ? null : cursor.getString(6);
        return new BuilderConversationProtocol.Turn(
                cursor.getInt(3),
                cursor.getString(1),
                cursor.getString(2),
                BuilderConversationProtocol.Participant.valueOf(cursor.getString(4)),
                recipient == null ? null : BuilderConversationProtocol.Participant.valueOf(recipient),
                replyTo,
                BuilderConversationProtocol.TurnType.valueOf(cursor.getString(7)),
                BuilderConversationProtocol.EvidenceStatus.valueOf(cursor.getString(8)),
                cursor.getLong(9),
                cursor.getString(10));
    }

    private static String requireDatabaseName(String name) {
        if (name == null || name.trim().isEmpty())
            throw new IllegalArgumentException("databaseName must not be blank");
        return name.trim();
    }
}
