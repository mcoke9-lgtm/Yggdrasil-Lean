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
public final class BuilderConversationStore extends SQLiteOpenHelper {
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
                BuilderRole.valueOf(cursor.getString(4)),
                recipient == null ? null : BuilderRole.valueOf(recipient),
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
