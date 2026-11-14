package org.yggdrasil.memorygate;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import java.util.UUID;

/**
 * Private, app-local store. Every retrieval decision and intended release is
 * committed to the local event table before protected content is returned.
 */
final class MemoryRecordStore extends SQLiteOpenHelper {
    private static final String DATABASE_NAME = "protected-memory.db";
    private static final int DATABASE_VERSION = 1;

    static final class MemoryRecord {
        final String id;
        final String sensitivity;
        final String content;

        MemoryRecord(String id, String sensitivity, String content) {
            this.id = id;
            this.sensitivity = sensitivity;
            this.content = content;
        }
    }

    MemoryRecordStore(Context context) {
        super(context, DATABASE_NAME, null, DATABASE_VERSION);
        setWriteAheadLoggingEnabled(false);
    }

    @Override
    public void onConfigure(SQLiteDatabase db) {
        super.onConfigure(db);
        db.setForeignKeyConstraintsEnabled(true);
        db.execSQL("PRAGMA synchronous=FULL");
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE memory_records ("
                + "record_id TEXT PRIMARY KEY NOT NULL, "
                + "sensitivity TEXT NOT NULL, "
                + "content TEXT NOT NULL)");
        db.execSQL("CREATE TABLE access_events ("
                + "event_id INTEGER PRIMARY KEY AUTOINCREMENT, "
                + "request_id TEXT NOT NULL, "
                + "event_type TEXT NOT NULL, "
                + "record_id TEXT NOT NULL, "
                + "outcome TEXT NOT NULL, "
                + "created_at INTEGER NOT NULL)");

        insertFixture(db, "project-fact-001", "ordinary",
                "Fictional test record: the project has an isolated memory gate.");
        insertFixture(db, "decision-history-001", "historical",
                "Fictional test record: retrieval requires a bounded authorization.");
        insertFixture(db, "restricted-test-001", "restricted",
                "Fictional restricted fixture. Never use real personal data in prototype tests.");
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        throw new IllegalStateException("No implicit database migration is permitted.");
    }

    /**
     * Returns null on denial, missing data, or any audit/storage failure.
     * No caller receives content unless the decision and release-intent events
     * commit successfully in the same database transaction.
     */
    synchronized MemoryRecord readWithGrant(
            MemoryAccessGrant grant, String requestedRecordId, long nowMillis) {
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            AuthorizationDecision.Outcome decision =
                    AuthorizationDecision.evaluate(grant, requestedRecordId, nowMillis);
            String requestId = grant == null || grant.requestId == null
                    ? "invalid-" + UUID.randomUUID() : grant.requestId;

            insertEvent(db, requestId, "ACCESS_DECISION", requestedRecordId,
                    decision.name(), nowMillis);

            if (decision != AuthorizationDecision.Outcome.ALLOW) {
                db.setTransactionSuccessful();
                return null;
            }

            MemoryRecord record = findRecord(db, requestedRecordId);
            if (record == null) {
                insertEvent(db, requestId, "RELEASE_INTENT", requestedRecordId,
                        "NOT_FOUND", nowMillis);
                db.setTransactionSuccessful();
                return null;
            }

            insertEvent(db, requestId, "RELEASE_INTENT", requestedRecordId,
                    "AUTHORIZED", nowMillis);
            db.setTransactionSuccessful();
            return record;
        } catch (RuntimeException failure) {
            // A failed audit write or database operation must never release data.
            return null;
        } finally {
            db.endTransaction();
        }
    }

    private static MemoryRecord findRecord(SQLiteDatabase db, String recordId) {
        try (Cursor cursor = db.query("memory_records",
                new String[]{"record_id", "sensitivity", "content"},
                "record_id = ?", new String[]{recordId},
                null, null, null, "1")) {
            if (!cursor.moveToFirst()) return null;
            return new MemoryRecord(cursor.getString(0), cursor.getString(1),
                    cursor.getString(2));
        }
    }

    private static void insertFixture(
            SQLiteDatabase db, String id, String sensitivity, String content) {
        ContentValues values = new ContentValues();
        values.put("record_id", id);
        values.put("sensitivity", sensitivity);
        values.put("content", content);
        db.insertOrThrow("memory_records", null, values);
    }

    private static void insertEvent(
            SQLiteDatabase db, String requestId, String type,
            String recordId, String outcome, long createdAt) {
        ContentValues values = new ContentValues();
        values.put("request_id", requestId);
        values.put("event_type", type);
        values.put("record_id", recordId == null ? "" : recordId);
        values.put("outcome", outcome);
        values.put("created_at", createdAt);
        db.insertOrThrow("access_events", null, values);
    }
}
