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

    /** Export a portable, versioned snapshot. The caller chooses where to store it. */
    public synchronized org.json.JSONObject exportSnapshot() {
        org.json.JSONObject root = new org.json.JSONObject();
        org.json.JSONArray events = new org.json.JSONArray();
        org.json.JSONArray receipts = new org.json.JSONArray();
        try {
            root.put("format", "yggdrasil-builder-continuity");
            root.put("format_version", 1);
            try (Cursor c = getReadableDatabase().rawQuery(
                    "SELECT sequence,event_id,event_type,actor,timestamp_ms,payload,previous_hash,event_hash FROM events ORDER BY sequence ASC", null)) {
                while (c.moveToNext()) {
                    org.json.JSONObject e = new org.json.JSONObject();
                    e.put("sequence", c.getLong(0)); e.put("event_id", c.getString(1));
                    e.put("event_type", c.getString(2)); e.put("actor", c.getString(3));
                    e.put("timestamp_ms", c.getLong(4)); e.put("payload", c.getString(5));
                    e.put("previous_hash", c.getString(6)); e.put("event_hash", c.getString(7));
                    events.put(e);
                }
            }
            try (Cursor c = getReadableDatabase().rawQuery(
                    "SELECT message_id,processed_at_ms,result_event_id FROM processed_messages ORDER BY processed_at_ms,message_id", null)) {
                while (c.moveToNext()) {
                    org.json.JSONObject r = new org.json.JSONObject();
                    r.put("message_id", c.getString(0)); r.put("processed_at_ms", c.getLong(1));
                    r.put("result_event_id", c.getString(2)); receipts.put(r);
                }
            }
            root.put("events", events); root.put("processed_messages", receipts);
            root.put("chain_verified", verifyChain());
            return root;
        } catch (org.json.JSONException e) { throw new IllegalStateException("Could not encode continuity snapshot", e); }
    }

    /**
     * Restore a validated snapshot atomically. Existing local continuity is replaced only
     * after every event hash/link and every receipt reference has been checked.
     */
    public synchronized void restoreSnapshot(org.json.JSONObject root) {
        if (root == null) throw new IllegalArgumentException("Snapshot is required");
        if (!"yggdrasil-builder-continuity".equals(root.optString("format"))
                || root.optInt("format_version", -1) != 1)
            throw new IllegalArgumentException("Unsupported continuity snapshot format");
        org.json.JSONArray events = root.optJSONArray("events");
        org.json.JSONArray receipts = root.optJSONArray("processed_messages");
        if (events == null || receipts == null || events.length() > 100000 || receipts.length() > 100000)
            throw new IllegalArgumentException("Snapshot collections are missing or exceed limits");

        java.util.HashSet<String> eventIds = new java.util.HashSet<>();
        java.util.HashSet<String> messageIds = new java.util.HashSet<>();
        java.util.HashSet<String> eventHashes = new java.util.HashSet<>();
        String previous = "";
        long expectedSequence = 1;
        java.util.ArrayList<ContentValues> eventRows = new java.util.ArrayList<>();
        java.util.ArrayList<ContentValues> receiptRows = new java.util.ArrayList<>();
        try {
            for (int i = 0; i < events.length(); i++) {
                org.json.JSONObject e = events.getJSONObject(i);
                long sequence = e.getLong("sequence");
                String id = e.getString("event_id"), type = e.getString("event_type");
                String actor = e.getString("actor"), payload = e.getString("payload");
                long time = e.getLong("timestamp_ms");
                String storedPrevious = e.getString("previous_hash");
                String storedHash = e.getString("event_hash");
                if (sequence != expectedSequence++ || time <= 0 || id.trim().isEmpty()
                        || type.trim().isEmpty() || actor.trim().isEmpty() || payload.length() > 65536
                        || !eventIds.add(id) || !storedPrevious.equals(previous))
                    throw new IllegalArgumentException("Invalid event ordering, identity, or hash link at item " + i);
                String computed = hash(id, type, actor, time, payload, previous);
                if (!computed.equals(storedHash) || !eventHashes.add(storedHash))
                    throw new IllegalArgumentException("Event hash verification failed at item " + i);
                ContentValues row = new ContentValues();
                row.put("sequence", sequence); row.put("event_id", id); row.put("event_type", type);
                row.put("actor", actor); row.put("timestamp_ms", time); row.put("payload", payload);
                row.put("previous_hash", storedPrevious); row.put("event_hash", storedHash);
                eventRows.add(row); previous = storedHash;
            }
            for (int i = 0; i < receipts.length(); i++) {
                org.json.JSONObject r = receipts.getJSONObject(i);
                String messageId = r.getString("message_id"), resultId = r.getString("result_event_id");
                long processedAt = r.getLong("processed_at_ms");
                if (messageId.trim().isEmpty() || processedAt <= 0 || !messageIds.add(messageId)
                        || !eventIds.contains(resultId))
                    throw new IllegalArgumentException("Invalid or duplicate processed-message receipt at item " + i);
                ContentValues row = new ContentValues();
                row.put("message_id", messageId); row.put("processed_at_ms", processedAt);
                row.put("result_event_id", resultId); receiptRows.add(row);
            }
        } catch (org.json.JSONException e) {
            throw new IllegalArgumentException("Malformed continuity snapshot", e);
        }

        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            db.delete("processed_messages", null, null);
            db.delete("events", null, null);
            for (ContentValues row : eventRows) db.insertOrThrow("events", null, row);
            for (ContentValues row : receiptRows) db.insertOrThrow("processed_messages", null, row);

            // Explicit sequence inserts do not lower SQLite's AUTOINCREMENT high-water mark.
            // Reset it inside the same transaction so the next append continues at N+1,
            // including when restoring an empty snapshot into a previously used database.
            db.delete("sqlite_sequence", "name=?", new String[]{"events"});
            if (!eventRows.isEmpty()) {
                ContentValues sequence = new ContentValues();
                sequence.put("name", "events");
                sequence.put("seq", eventRows.size());
                db.insertOrThrow("sqlite_sequence", null, sequence);
            }
            db.setTransactionSuccessful();
        } finally { db.endTransaction(); }
        if (!verifyChain()) throw new IllegalStateException("Restored journal failed post-restore integrity verification");
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