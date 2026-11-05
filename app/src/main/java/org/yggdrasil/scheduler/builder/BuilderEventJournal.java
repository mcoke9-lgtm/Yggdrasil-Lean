package org.yggdrasil.scheduler.builder;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;

/**
 * Local append-only journal for Builder events.
 * Event hashes link each record to the previous record; this is tamper-evident,
 * not a substitute for authenticated backups or device security.
 */
public final class BuilderEventJournal extends SQLiteOpenHelper {
    private static final String DB_NAME = "yggdrasil_builder_continuity.db";
    private static final int DB_VERSION = 1;

    public BuilderEventJournal(Context context) { super(context, DB_NAME, null, DB_VERSION); }

    @Override public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE events (sequence INTEGER PRIMARY KEY AUTOINCREMENT, event_id TEXT NOT NULL UNIQUE, event_type TEXT NOT NULL, actor TEXT NOT NULL, timestamp_ms INTEGER NOT NULL, payload TEXT NOT NULL, previous_hash TEXT NOT NULL, event_hash TEXT NOT NULL UNIQUE)");
        db.execSQL("CREATE TABLE processed_messages (message_id TEXT PRIMARY KEY, processed_at_ms INTEGER NOT NULL, result_event_id TEXT NOT NULL)");
    }

    @Override public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        throw new IllegalStateException("No automatic destructive journal migration is defined");
    }

    /** Returns false when eventId already exists; duplicate events are not appended. */
    public synchronized boolean append(String eventId, String eventType, String actor,
                                       long timestampMs, String payload) {
        require(eventId, "eventId"); require(eventType, "eventType");
        require(actor, "actor"); require(payload, "payload");
        if (timestampMs <= 0) throw new IllegalArgumentException("timestampMs must be positive");
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            if (exists(db, "events", "event_id", eventId)) return false;
            String previous = latestHash(db);
            String hash = hash(eventId, eventType, actor, timestampMs, payload, previous);
            ContentValues values = new ContentValues();
            values.put("event_id", eventId); values.put("event_type", eventType);
            values.put("actor", actor); values.put("timestamp_ms", timestampMs);
            values.put("payload", payload); values.put("previous_hash", previous);
            values.put("event_hash", hash);
            db.insertOrThrow("events", null, values);
            db.setTransactionSuccessful();
            return true;
        } finally { db.endTransaction(); }
    }

    /** Atomically marks a message processed and appends its receipt event once. */
    public synchronized boolean recordMessageOnce(BuilderMessage message, long processedAtMs) {
        if (message == null) throw new IllegalArgumentException("message is required");
        if (processedAtMs <= 0) throw new IllegalArgumentException("processedAtMs must be positive");
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            if (exists(db, "processed_messages", "message_id", message.getMessageId())) return false;
            String eventId = "message-received:" + message.getMessageId();
            String payload = message.getSender() + " -> " + message.getRecipient()
                    + " [" + message.getContentType() + "] " + message.getPayload();
            String previous = latestHash(db);
            String eventHash = hash(eventId, "MESSAGE_RECEIVED", message.getRecipient(),
                    processedAtMs, payload, previous);
            ContentValues event = new ContentValues();
            event.put("event_id", eventId); event.put("event_type", "MESSAGE_RECEIVED");
            event.put("actor", message.getRecipient()); event.put("timestamp_ms", processedAtMs);
            event.put("payload", payload); event.put("previous_hash", previous);
            event.put("event_hash", eventHash);
            db.insertOrThrow("events", null, event);
            ContentValues receipt = new ContentValues();
            receipt.put("message_id", message.getMessageId());
            receipt.put("processed_at_ms", processedAtMs);
            receipt.put("result_event_id", eventId);
            db.insertOrThrow("processed_messages", null, receipt);
            db.setTransactionSuccessful();
            return true;
        } finally { db.endTransaction(); }
    }

    public synchronized long eventCount() {
        try (Cursor c = getReadableDatabase().rawQuery("SELECT COUNT(*) FROM events", null)) {
            return c.moveToFirst() ? c.getLong(0) : 0;
        }
    }

    public synchronized List<Event> recentEvents(int limit) {
        if (limit < 1 || limit > 1000) throw new IllegalArgumentException("limit must be 1..1000");
        List<Event> result = new ArrayList<>();
        try (Cursor c = getReadableDatabase().rawQuery(
                "SELECT sequence,event_id,event_type,actor,timestamp_ms,payload,previous_hash,event_hash FROM events ORDER BY sequence DESC LIMIT ?",
                new String[]{String.valueOf(limit)})) {
            while (c.moveToNext()) result.add(new Event(c.getLong(0), c.getString(1),
                    c.getString(2), c.getString(3), c.getLong(4), c.getString(5),
                    c.getString(6), c.getString(7)));
        }
        return result;
    }

    /** Recomputes the complete chain and returns true only if every link matches. */
    public synchronized boolean verifyChain() {
        String previous = "";
        try (Cursor c = getReadableDatabase().rawQuery(
                "SELECT event_id,event_type,actor,timestamp_ms,payload,previous_hash,event_hash FROM events ORDER BY sequence ASC", null)) {
            while (c.moveToNext()) {
                String eventPrevious = c.getString(5);
                String expected = hash(c.getString(0), c.getString(1), c.getString(2),
                        c.getLong(3), c.getString(4), previous);
                if (!previous.equals(eventPrevious) || !expected.equals(c.getString(6))) return false;
                previous = c.getString(6);
            }
        }
        return true;
    }

    private static boolean exists(SQLiteDatabase db, String table, String column, String value) {
        try (Cursor c = db.query(table, new String[]{column}, column + "=?",
                new String[]{value}, null, null, null, "1")) { return c.moveToFirst(); }
    }

    private static String latestHash(SQLiteDatabase db) {
        try (Cursor c = db.rawQuery("SELECT event_hash FROM events ORDER BY sequence DESC LIMIT 1", null)) {
            return c.moveToFirst() ? c.getString(0) : "";
        }
    }

    private static String hash(String id, String type, String actor, long time,
                               String payload, String previous) {
        String canonical = id + "\n" + type + "\n" + actor + "\n" + time
                + "\n" + payload + "\n" + previous;
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(canonical.getBytes(StandardCharsets.UTF_8));
            StringBuilder out = new StringBuilder();
            for (byte b : digest) out.append(String.format("%02x", b & 0xff));
            return out.toString();
        } catch (Exception e) { throw new IllegalStateException("SHA-256 unavailable", e); }
    }

    private static void require(String value, String field) {
        if (value == null || value.trim().isEmpty())
            throw new IllegalArgumentException(field + " must not be blank");
    }

    public static final class Event {
        public final long sequence, timestampMs;
        public final String eventId, eventType, actor, payload, previousHash, eventHash;
        Event(long sequence, String eventId, String eventType, String actor, long timestampMs,
              String payload, String previousHash, String eventHash) {
            this.sequence=sequence; this.eventId=eventId; this.eventType=eventType;
            this.actor=actor; this.timestampMs=timestampMs; this.payload=payload;
            this.previousHash=previousHash; this.eventHash=eventHash;
        }
    }
}