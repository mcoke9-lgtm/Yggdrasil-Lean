package org.yggdrasil.scheduler.builder;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import org.json.JSONArray;
import org.json.JSONObject;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Append-only subject associations for conversation threads.
 *
 * Kept in a separate database so the existing conversation journal schema and
 * history are never rewritten. One thread may accumulate multiple subjects and
 * subheadings while its canonical turns remain in BuilderConversationStore.
 */
public final class BuilderSubjectThreadIndex extends SQLiteOpenHelper {
    private static final int DATABASE_VERSION = 1;
    private static final String TABLE = "subject_thread_events";

    public enum EventType { SUBJECT_ATTACHED, SUBHEADING_ATTACHED, PARTICIPANT_SELECTED }

    public static final class Event {
        public final String eventId, threadId, subjectId, subheadingId, participant;
        public final EventType type;
        public final long timestampEpochMillis;

        public Event(String eventId, String threadId, String subjectId,
                     EventType type, String subheadingId, String participant,
                     long timestampEpochMillis) {
            this.eventId = required(eventId, "eventId");
            this.threadId = required(threadId, "threadId");
            this.subjectId = required(subjectId, "subjectId");
            if (type == null) throw new IllegalArgumentException("type is required");
            this.type = type;
            this.subheadingId = optional(subheadingId);
            this.participant = optional(participant);
            if (timestampEpochMillis <= 0) throw new IllegalArgumentException("timestamp must be positive");
            this.timestampEpochMillis = timestampEpochMillis;
            if (type == EventType.SUBHEADING_ATTACHED && this.subheadingId == null)
                throw new IllegalArgumentException("subheadingId is required");
            if (type == EventType.PARTICIPANT_SELECTED && this.participant == null)
                throw new IllegalArgumentException("participant is required");
            if (type != EventType.SUBHEADING_ATTACHED && this.subheadingId != null)
                throw new IllegalArgumentException("subheadingId is only valid for SUBHEADING_ATTACHED");
            if (type != EventType.PARTICIPANT_SELECTED && this.participant != null)
                throw new IllegalArgumentException("participant is only valid for PARTICIPANT_SELECTED");
        }
    }

    public BuilderSubjectThreadIndex(Context context) {
        super(context.getApplicationContext(), "builder-subject-thread-index.db", null, DATABASE_VERSION);
    }

    @Override public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE " + TABLE + " ("
                + "_id INTEGER PRIMARY KEY AUTOINCREMENT, event_id TEXT NOT NULL UNIQUE, thread_id TEXT NOT NULL, subject_id TEXT NOT NULL, "
                + "event_type TEXT NOT NULL, subheading_id TEXT, participant TEXT, "
                + "timestamp_epoch_millis INTEGER NOT NULL)");
        db.execSQL("CREATE INDEX subject_thread_order ON " + TABLE + "(thread_id, _id)");
    }

    @Override public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        throw new IllegalStateException("No subject-thread-index migration from " + oldVersion + " to " + newVersion);
    }

    /** Appends one immutable association event; duplicate event IDs are rejected. */
    public synchronized void append(Event event) {
        if (event == null) throw new IllegalArgumentException("event is required");
        appendAll(Collections.singletonList(event));
    }

    /**
     * Appends a related set of metadata events atomically. Either every association
     * is stored, or none is; the canonical conversation journal is not involved.
     */
    public synchronized void appendAll(List<Event> events) {
        if (events == null || events.isEmpty())
            throw new IllegalArgumentException("at least one event is required");
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            for (Event event : events) {
                if (event == null) throw new IllegalArgumentException("event is required");
                ContentValues values = new ContentValues();
                values.put("event_id", event.eventId);
                values.put("thread_id", event.threadId);
                values.put("subject_id", event.subjectId);
                values.put("event_type", event.type.name());
                if (event.subheadingId == null) values.putNull("subheading_id");
                else values.put("subheading_id", event.subheadingId);
                if (event.participant == null) values.putNull("participant");
                else values.put("participant", event.participant);
                values.put("timestamp_epoch_millis", event.timestampEpochMillis);
                db.insertOrThrow(TABLE, null, values);
            }
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }

    /** Returns immutable event history for one conversation thread. */
    public synchronized List<Event> readThread(String threadId) {
        String id = required(threadId, "threadId");
        List<Event> events = new ArrayList<>();
        try (Cursor c = getReadableDatabase().query(TABLE, null, "thread_id = ?",
                new String[]{id}, null, null, "_id ASC")) {
            while (c.moveToNext()) {
                String subheading = c.isNull(5) ? null : c.getString(5);
                String participant = c.isNull(6) ? null : c.getString(6);
                events.add(new Event(c.getString(1), c.getString(2), c.getString(3),
                        EventType.valueOf(c.getString(4)), subheading, participant, c.getLong(7)));
            }
        }
        return Collections.unmodifiableList(events);
    }

    /** Portable metadata-only snapshot; canonical conversation turns are not duplicated. */
    public synchronized JSONObject exportSnapshot() {
        try {
            JSONObject root = new JSONObject();
            JSONArray events = new JSONArray();
            root.put("format", "yggdrasil-subject-thread-index");
            root.put("format_version", 1);
            try (Cursor c = getReadableDatabase().query(TABLE, null, null, null, null, null,
                    "_id ASC")) {
                while (c.moveToNext()) {
                    JSONObject item = new JSONObject();
                    item.put("event_id", c.getString(1));
                    item.put("thread_id", c.getString(2));
                    item.put("subject_id", c.getString(3));
                    item.put("event_type", c.getString(4));
                    item.put("subheading_id", c.isNull(5) ? JSONObject.NULL : c.getString(5));
                    item.put("participant", c.isNull(6) ? JSONObject.NULL : c.getString(6));
                    item.put("timestamp_epoch_millis", c.getLong(7));
                    events.put(item);
                }
            }
            root.put("event_count", events.length());
            root.put("events", events);
            root.put("content_sha256", sha256(root.toString()));
            return root;
        } catch (Exception e) {
            throw new IllegalStateException("Could not export subject-thread index", e);
        }
    }

    private static String sha256(String value) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        StringBuilder out = new StringBuilder();
        for (byte b : digest) out.append(String.format(java.util.Locale.ROOT, "%02x", b & 0xff));
        return out.toString();
    }
    private static String required(String value, String field) {
        if (value == null || value.trim().isEmpty()) throw new IllegalArgumentException(field + " must not be blank");
        return value.trim();
    }
    private static String optional(String value) {
        return value == null || value.trim().isEmpty() ? null : value.trim();
    }
}
