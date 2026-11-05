package org.yggdrasil.scheduler.builder;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Generation-bound append-only overlay for an immutable recovery baseline.
 *
 * One SQLite file stores both post-baseline conversation turns and subject events.
 * It is deliberately not wired into application startup. The two baseline files
 * are opened read-only and their canonical record-array digests are rechecked
 * against the supplied generation identity before data is read or written.
 *
 * SQLite transactions provide cross-process write serialization; Java monitors
 * are not used as a correctness boundary.
 */
public final class BuilderRecoveryOverlayStore extends SQLiteOpenHelper {
    private static final int DATABASE_VERSION = 1;
    private static final String META = "overlay_metadata";
    private static final String TURNS = "conversation_turns";
    private static final String EVENTS = "subject_thread_events";
    private static final String FORMAT = "yggdrasil-recovery-overlay";
    private static final String[] TURN_COLUMNS = {
            "_id", "thread_id", "turn_id", "protocol_version", "speaker",
            "recipient", "reply_to_turn_id", "turn_type", "evidence_status",
            "timestamp_epoch_millis", "body"
    };

    public static final class Identity {
        public final String generationId;
        public final String conversationBaselineSha256;
        public final String subjectBaselineSha256;

        private Identity(String generationId, String conversationDigest, String subjectDigest) {
            this.generationId = generationId;
            this.conversationBaselineSha256 = conversationDigest;
            this.subjectBaselineSha256 = subjectDigest;
        }
    }

    private final Context app;
    private final String conversationBaselineName;
    private final String subjectBaselineName;
    private final String generationId;
    private final String conversationDigest;
    private final String subjectDigest;
    private final boolean allowCreate;

    public static String databaseNameForGeneration(String generationId) {
        return "builder-recovery-" + requireGenerationId(generationId) + "-overlay.db";
    }

    private BuilderRecoveryOverlayStore(Context context, String conversationBaselineName,
                                        String subjectBaselineName, String generationId,
                                        String conversationBaselineSha256,
                                        String subjectBaselineSha256, boolean allowCreate) {
        super(requireContext(context), databaseNameForGeneration(generationId), null,
                DATABASE_VERSION);
        this.app = context.getApplicationContext();
        this.conversationBaselineName = requireDatabaseName(conversationBaselineName);
        this.subjectBaselineName = requireDatabaseName(subjectBaselineName);
        this.generationId = requireGenerationId(generationId);
        this.allowCreate = allowCreate;
        if (!("builder-recovery-" + this.generationId + "-conversations.db")
                    .equals(this.conversationBaselineName)
                || !("builder-recovery-" + this.generationId + "-subjects.db")
                    .equals(this.subjectBaselineName))
            throw new IllegalArgumentException("Baseline database names do not match generation identity");
        this.conversationDigest = requireDigest(conversationBaselineSha256,
                "conversation baseline digest");
        this.subjectDigest = requireDigest(subjectBaselineSha256,
                "subject baseline digest");
        verifyBaselineFiles();
    }

    /** Explicit first-time creation. Existing overlay files are never overwritten or reset. */
    public static BuilderRecoveryOverlayStore create(Context context,
            String conversationBaselineName, String subjectBaselineName, String generationId,
            String conversationBaselineSha256, String subjectBaselineSha256) {
        Context app = requireContext(context);
        File file = app.getDatabasePath(databaseNameForGeneration(generationId));
        if (file.exists())
            throw new IllegalStateException("Recovery overlay already exists; refusing to replace it");
        BuilderRecoveryOverlayStore store = new BuilderRecoveryOverlayStore(context,
                conversationBaselineName, subjectBaselineName, generationId,
                conversationBaselineSha256, subjectBaselineSha256, true);
        try {
            store.getWritableDatabase();
            return store;
        } catch (RuntimeException failure) {
            store.close();
            throw failure;
        }
    }

    /** Opens an existing overlay only. A missing file is not silently recreated. */
    public static BuilderRecoveryOverlayStore openExisting(Context context,
            String conversationBaselineName, String subjectBaselineName, String generationId,
            String conversationBaselineSha256, String subjectBaselineSha256) {
        Context app = requireContext(context);
        File file = app.getDatabasePath(databaseNameForGeneration(generationId));
        if (!file.isFile())
            throw new IllegalStateException("Recovery overlay is missing; refusing to create an empty replacement");
        BuilderRecoveryOverlayStore store = new BuilderRecoveryOverlayStore(context,
                conversationBaselineName, subjectBaselineName, generationId,
                conversationBaselineSha256, subjectBaselineSha256, false);
        try {
            store.getReadableDatabase();
            return store;
        } catch (RuntimeException failure) {
            store.close();
            throw failure;
        }
    }

    @Override public void onCreate(SQLiteDatabase db) {
        if (!allowCreate)
            throw new IllegalStateException("Recovery overlay is missing; refusing implicit recreation");
        db.execSQL("CREATE TABLE " + META + " (
                + "singleton INTEGER PRIMARY KEY CHECK(singleton = 1), "
                + "format TEXT NOT NULL, schema_version INTEGER NOT NULL, "
                + "generation_id TEXT NOT NULL, conversation_sha256 TEXT NOT NULL, "
                + "subject_sha256 TEXT NOT NULL, created_at_epoch_millis INTEGER NOT NULL)");
        db.execSQL("CREATE TABLE " + TURNS + " ("
                + "_id INTEGER PRIMARY KEY AUTOINCREMENT, "
                + "thread_id TEXT NOT NULL, turn_id TEXT NOT NULL, "
                + "protocol_version INTEGER NOT NULL, speaker TEXT NOT NULL, "
                + "recipient TEXT, reply_to_turn_id TEXT, turn_type TEXT NOT NULL, "
                + "evidence_status TEXT NOT NULL, timestamp_epoch_millis INTEGER NOT NULL, "
                + "body TEXT NOT NULL, UNIQUE(thread_id, turn_id))");
        db.execSQL("CREATE INDEX overlay_conversation_thread_order ON "
                + TURNS + "(thread_id, _id)");
        db.execSQL("CREATE TABLE " + EVENTS + " ("
                + "_id INTEGER PRIMARY KEY AUTOINCREMENT, event_id TEXT NOT NULL UNIQUE, "
                + "thread_id TEXT NOT NULL, subject_id TEXT NOT NULL, event_type TEXT NOT NULL, "
                + "subheading_id TEXT, participant TEXT, timestamp_epoch_millis INTEGER NOT NULL)");
        db.execSQL("CREATE INDEX overlay_subject_thread_order ON "
                + EVENTS + "(thread_id, _id)");
        ContentValues meta = new ContentValues();
        meta.put("singleton", 1);
        meta.put("format", FORMAT);
        meta.put("schema_version", DATABASE_VERSION);
        meta.put("generation_id", generationId);
        meta.put("conversation_sha256", conversationDigest);
        meta.put("subject_sha256", subjectDigest);
        meta.put("created_at_epoch_millis", System.currentTimeMillis());
        db.insertOrThrow(META, null, meta);
    }

    @Override public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        throw new IllegalStateException("No recovery-overlay migration from "
                + oldVersion + " to " + newVersion);
    }

    @Override public void onOpen(SQLiteDatabase db) {
        super.onOpen(db);
        verifyMetadata(db);
        verifyBaselineDigests();
    }

    /** The immutable generation binding carried by this overlay. */
    public Identity identity() {
        SQLiteDatabase db = getReadableDatabase();
        verifyMetadata(db);
        return new Identity(generationId, conversationDigest, subjectDigest);
    }

    /** Atomically appends one turn after validating the complete baseline + overlay thread. */
    public void append(BuilderConversationProtocol.Turn turn) {
        if (turn == null) throw new IllegalArgumentException("turn is required");
        BuilderConversationProtocol.validateTurn(turn);
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        SQLiteDatabase baseline = null;
        try {
            baseline = baselineConversation();
            List<BuilderConversationProtocol.Turn> candidate =
                    readConversationThread(baseline, turn.threadId);
            candidate.addAll(readOverlayThread(db, turn.threadId));
            candidate.add(turn);
            BuilderConversationProtocol.validateThread(candidate);
            db.insertOrThrow(TURNS, null, turnValues(turn));
            db.setTransactionSuccessful();
        } finally {
            if (baseline != null) baseline.close();
            db.endTransaction();
        }
    }

    /** Atomically appends a subject-event group; either all events commit or none do. */
    public void appendAll(List<BuilderSubjectThreadIndex.Event> events) {
        if (events == null || events.isEmpty())
            throw new IllegalArgumentException("at least one subject event is required");
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            Set<String> ids = new LinkedHashSet<>();
            for (BuilderSubjectThreadIndex.Event event : events) {
                if (event == null) throw new IllegalArgumentException("subject event is required");
                if (!ids.add(event.eventId))
                    throw new IllegalArgumentException("Duplicate subject event ID in append batch: " + event.eventId);
                if (baselineHasEvent(event.eventId) || overlayHasEvent(db, event.eventId))
                    throw new IllegalArgumentException("Duplicate subject event ID across baseline and overlay: " + event.eventId);
            }
            for (BuilderSubjectThreadIndex.Event event : events)
                db.insertOrThrow(EVENTS, null, eventValues(event));
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }

    /** Returns baseline turns followed by overlay turns in append order. */
    public List<BuilderConversationProtocol.Turn> readThread(String threadId) {
        String id = required(threadId, "threadId");
        SQLiteDatabase baseline = baselineConversation();
        try {
            List<BuilderConversationProtocol.Turn> merged =
                    readConversationThread(baseline, id);
            merged.addAll(readOverlayThread(getReadableDatabase(), id));
            return Collections.unmodifiableList(merged);
        } finally { baseline.close(); }
    }

    /** Returns the union of thread IDs, with overlay-active threads first. */
    public List<String> listThreadIds() {
        LinkedHashSet<String> ids = new LinkedHashSet<>();
        SQLiteDatabase overlay = getReadableDatabase();
        try (Cursor c = overlay.rawQuery("SELECT thread_id FROM " + TURNS
                + " GROUP BY thread_id ORDER BY MAX(_id) DESC", null)) {
            while (c.moveToNext()) ids.add(c.getString(0));
        }
        SQLiteDatabase base = baselineConversation();
        try (Cursor c = base.rawQuery("SELECT thread_id FROM conversation_turns "
                + "GROUP BY thread_id ORDER BY MAX(_id) DESC", null)) {
            while (c.moveToNext()) ids.add(c.getString(0));
        } finally { base.close(); }
        return Collections.unmodifiableList(new ArrayList<>(ids));
    }

    /** Returns baseline subject events followed by overlay events for one thread. */
    public List<BuilderSubjectThreadIndex.Event> readSubjectThread(String threadId) {
        String id = required(threadId, "threadId");
        List<BuilderSubjectThreadIndex.Event> merged = readBaselineEvents(id);
        merged.addAll(readOverlayEvents(getReadableDatabase(), id));
        return Collections.unmodifiableList(merged);
    }

    /** Exports a merged canonical conversation snapshot without modifying either layer. */
    public JSONObject exportConversationSnapshot() {
        try {
            JSONObject root = new JSONObject();
            JSONArray threads = new JSONArray();
            int total = 0;
            for (String threadId : listThreadIds()) {
                JSONArray turns = new JSONArray();
                for (BuilderConversationProtocol.Turn turn : readThread(threadId)) {
                    turns.put(turnJson(turn));
                    total++;
                }
                JSONObject thread = new JSONObject();
                thread.put("thread_id", threadId);
                thread.put("turns", turns);
                threads.put(thread);
            }
            root.put("format", "yggdrasil-nine-builder-conversations");
            root.put("format_version", 1);
            root.put("exported_at_epoch_millis", System.currentTimeMillis());
            root.put("thread_count", threads.length());
            root.put("turn_count", total);
            root.put("threads", threads);
            root.put("content_sha256", sha256(root.toString()));
            return root;
        } catch (Exception e) {
            throw new IllegalStateException("Could not export merged conversation snapshot", e);
        }
    }

    /** Exports merged metadata in baseline-then-overlay append order. */
    public JSONObject exportSubjectSnapshot() {
        try {
            JSONArray rows = new JSONArray();
            SQLiteDatabase base = baselineSubject();
            try (Cursor c = base.query(EVENTS, null, null, null, null, null, "_id ASC")) {
                while (c.moveToNext()) rows.put(eventJson(eventFromCursor(c)));
            } finally { base.close(); }
            SQLiteDatabase overlay = getReadableDatabase();
            try (Cursor c = overlay.query(EVENTS, null, null, null, null, null, "_id ASC")) {
                while (c.moveToNext()) rows.put(eventJson(eventFromCursor(c)));
            }
            JSONObject root = new JSONObject();
            root.put("format", "yggdrasil-subject-thread-index");
            root.put("format_version", 1);
            root.put("event_count", rows.length());
            root.put("events", rows);
            root.put("content_sha256", sha256(root.toString()));
            return root;
        } catch (Exception e) {
            throw new IllegalStateException("Could not export merged subject snapshot", e);
        }
    }

    private SQLiteDatabase baselineConversation() {
        return openBaseline(conversationBaselineName);
    }

    private SQLiteDatabase baselineSubject() {
        return openBaseline(subjectBaselineName);
    }

    private SQLiteDatabase openBaseline(String name) {
        File file = app.getDatabasePath(name);
        if (!file.isFile()) throw new IllegalStateException("Recovery baseline database is missing: " + name);
        return SQLiteDatabase.openDatabase(file.getAbsolutePath(), null, SQLiteDatabase.OPEN_READONLY);
    }

    private void verifyBaselineFiles() {
        if (!app.getDatabasePath(conversationBaselineName).isFile()
                || !app.getDatabasePath(subjectBaselineName).isFile())
            throw new IllegalStateException("Both immutable recovery baseline databases must exist");
    }

    private void verifyBaselineDigests() {
        SQLiteDatabase conversations = baselineConversation();
        SQLiteDatabase subjects = baselineSubject();
        try {
            JSONArray threads = new JSONArray();
            try (Cursor c = conversations.rawQuery("SELECT thread_id FROM conversation_turns "
                    + "GROUP BY thread_id ORDER BY MAX(_id) DESC", null)) {
                while (c.moveToNext()) {
                    String id = c.getString(0);
                    JSONArray turns = new JSONArray();
                    try (Cursor rows = conversations.query("conversation_turns", TURN_COLUMNS,
                            "thread_id = ?", new String[]{id}, null, null, "_id ASC")) {
                        while (rows.moveToNext()) turns.put(turnJson(turnFromCursor(rows)));
                    }
                    JSONObject thread = new JSONObject();
                    thread.put("thread_id", id);
                    thread.put("turns", turns);
                    threads.put(thread);
                }
            }
            JSONArray events = new JSONArray();
            try (Cursor c = subjects.query(EVENTS, null, null, null, null, null, "_id ASC")) {
                while (c.moveToNext()) events.put(eventJson(eventFromCursor(c)));
            }
            if (!conversationDigest.equals(sha256(threads.toString()))
                    || !subjectDigest.equals(sha256(events.toString())))
                throw new IllegalStateException("Immutable recovery baseline digest mismatch");
        } catch (Exception e) {
            if (e instanceof IllegalStateException) throw (IllegalStateException) e;
            throw new IllegalStateException("Could not verify immutable recovery baseline", e);
        } finally {
            conversations.close();
            subjects.close();
        }
    }

    private void verifyMetadata(SQLiteDatabase db) {
        try (Cursor c = db.query(META, null, "singleton = 1", null, null, null, null)) {
            if (!c.moveToFirst()
                    || !FORMAT.equals(c.getString(c.getColumnIndexOrThrow("format")))
                    || DATABASE_VERSION != c.getInt(c.getColumnIndexOrThrow("schema_version"))
                    || !generationId.equals(c.getString(c.getColumnIndexOrThrow("generation_id")))
                    || !conversationDigest.equals(c.getString(c.getColumnIndexOrThrow("conversation_sha256")))
                    || !subjectDigest.equals(c.getString(c.getColumnIndexOrThrow("subject_sha256"))))
                throw new IllegalStateException("Recovery overlay identity does not match selected generation");
            if (c.moveToNext())
                throw new IllegalStateException("Recovery overlay metadata is not a singleton");
        }
    }

    private List<BuilderConversationProtocol.Turn> readOverlayThread(SQLiteDatabase db, String threadId) {
        List<BuilderConversationProtocol.Turn> turns = new ArrayList<>();
        try (Cursor c = db.query(TURNS, TURN_COLUMNS, "thread_id = ?",
                new String[]{threadId}, null, null, "_id ASC")) {
            while (c.moveToNext()) turns.add(turnFromCursor(c));
        }
        return turns;
    }

    private List<BuilderConversationProtocol.Turn> readConversationThread(
            SQLiteDatabase db, String threadId) {
        List<BuilderConversationProtocol.Turn> turns = new ArrayList<>();
        try (Cursor c = db.query(TURNS, TURN_COLUMNS, "thread_id = ?",
                new String[]{threadId}, null, null, "_id ASC")) {
            while (c.moveToNext()) turns.add(turnFromCursor(c));
        }
        return turns;
    }

    private List<BuilderSubjectThreadIndex.Event> readBaselineEvents(String threadId) {
        List<BuilderSubjectThreadIndex.Event> events = new ArrayList<>();
        SQLiteDatabase db = baselineSubject();
        try (Cursor c = db.query(EVENTS, null, "thread_id = ?", new String[]{threadId},
                null, null, "_id ASC")) {
            while (c.moveToNext()) events.add(eventFromCursor(c));
        } finally { db.close(); }
        return events;
    }

    private List<BuilderSubjectThreadIndex.Event> readOverlayEvents(SQLiteDatabase db, String threadId) {
        List<BuilderSubjectThreadIndex.Event> events = new ArrayList<>();
        try (Cursor c = db.query(EVENTS, null, "thread_id = ?", new String[]{threadId},
                null, null, "_id ASC")) {
            while (c.moveToNext()) events.add(eventFromCursor(c));
        }
        return events;
    }

    private boolean baselineHasEvent(String eventId) {
        SQLiteDatabase db = baselineSubject();
        try (Cursor c = db.query(EVENTS, new String[]{"event_id"}, "event_id = ?",
                new String[]{eventId}, null, null, null)) {
            return c.moveToFirst();
        } finally { db.close(); }
    }

    private static boolean overlayHasEvent(SQLiteDatabase db, String eventId) {
        try (Cursor c = db.query(EVENTS, new String[]{"event_id"}, "event_id = ?",
                new String[]{eventId}, null, null, null)) {
            return c.moveToFirst();
        }
    }

    private static ContentValues turnValues(BuilderConversationProtocol.Turn turn) {
        ContentValues v = new ContentValues();
        v.put("thread_id", turn.threadId); v.put("turn_id", turn.turnId);
        v.put("protocol_version", turn.protocolVersion); v.put("speaker", turn.speaker.name());
        if (turn.recipient == null) v.putNull("recipient"); else v.put("recipient", turn.recipient.name());
        if (turn.replyToTurnId == null) v.putNull("reply_to_turn_id"); else v.put("reply_to_turn_id", turn.replyToTurnId);
        v.put("turn_type", turn.type.name()); v.put("evidence_status", turn.evidenceStatus.name());
        v.put("timestamp_epoch_millis", turn.timestampEpochMillis); v.put("body", turn.body);
        return v;
    }

    private static ContentValues eventValues(BuilderSubjectThreadIndex.Event event) {
        ContentValues v = new ContentValues();
        v.put("event_id", event.eventId); v.put("thread_id", event.threadId);
        v.put("subject_id", event.subjectId); v.put("event_type", event.type.name());
        if (event.subheadingId == null) v.putNull("subheading_id"); else v.put("subheading_id", event.subheadingId);
        if (event.participant == null) v.putNull("participant"); else v.put("participant", event.participant);
        v.put("timestamp_epoch_millis", event.timestampEpochMillis);
        return v;
    }

    private static BuilderConversationProtocol.Turn turnFromCursor(Cursor c) {
        String recipient = c.isNull(5) ? null : c.getString(5);
        String reply = c.isNull(6) ? null : c.getString(6);
        return new BuilderConversationProtocol.Turn(c.getInt(3), c.getString(1), c.getString(2),
                BuilderConversationProtocol.Participant.valueOf(c.getString(4)),
                recipient == null ? null : BuilderConversationProtocol.Participant.valueOf(recipient),
                reply, BuilderConversationProtocol.TurnType.valueOf(c.getString(7)),
                BuilderConversationProtocol.EvidenceStatus.valueOf(c.getString(8)),
                c.getLong(9), c.getString(10));
    }

    private static BuilderSubjectThreadIndex.Event eventFromCursor(Cursor c) {
        String subheading = c.isNull(5) ? null : c.getString(5);
        String participant = c.isNull(6) ? null : c.getString(6);
        return new BuilderSubjectThreadIndex.Event(c.getString(1), c.getString(2), c.getString(3),
                BuilderSubjectThreadIndex.EventType.valueOf(c.getString(4)),
                subheading, participant, c.getLong(7));
    }

    private static JSONObject turnJson(BuilderConversationProtocol.Turn t) throws Exception {
        JSONObject row = new JSONObject();
        row.put("protocol_version", t.protocolVersion); row.put("thread_id", t.threadId);
        row.put("turn_id", t.turnId); row.put("speaker", t.speaker.name());
        row.put("recipient", t.recipient == null ? JSONObject.NULL : t.recipient.name());
        row.put("reply_to_turn_id", t.replyToTurnId == null ? JSONObject.NULL : t.replyToTurnId);
        row.put("turn_type", t.type.name()); row.put("evidence_status", t.evidenceStatus.name());
        row.put("timestamp_epoch_millis", t.timestampEpochMillis); row.put("body", t.body);
        return row;
    }

    private static JSONObject eventJson(BuilderSubjectThreadIndex.Event e) throws Exception {
        JSONObject row = new JSONObject();
        row.put("event_id", e.eventId); row.put("thread_id", e.threadId);
        row.put("subject_id", e.subjectId); row.put("event_type", e.type.name());
        row.put("subheading_id", e.subheadingId == null ? JSONObject.NULL : e.subheadingId);
        row.put("participant", e.participant == null ? JSONObject.NULL : e.participant);
        row.put("timestamp_epoch_millis", e.timestampEpochMillis);
        return row;
    }

    private static String sha256(String value) {
        try {
            byte[] bytes = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder out = new StringBuilder();
            for (byte b : bytes) out.append(String.format(java.util.Locale.ROOT, "%02x", b & 0xff));
            return out.toString();
        } catch (Exception e) { throw new IllegalStateException("SHA-256 is unavailable", e); }
    }

    private static Context requireContext(Context context) {
        if (context == null) throw new IllegalArgumentException("context is required");
        return context.getApplicationContext();
    }
    private static String requireGenerationId(String id) {
        if (id == null || !id.matches("[A-Za-z0-9_-]{1,48}"))
            throw new IllegalArgumentException("generationId must contain 1-48 letters, digits, '_' or '-'");
        return id;
    }
    private static String requireDatabaseName(String name) {
        if (name == null || !name.matches("[A-Za-z0-9_.-]{1,120}"))
            throw new IllegalArgumentException("database name is invalid");
        return name;
    }
    private static String requireDigest(String value, String label) {
        if (value == null || !value.matches("[0-9a-f]{64}"))
            throw new IllegalArgumentException(label + " must be lowercase SHA-256");
        return value;
    }
    private static String required(String value, String field) {
        if (value == null || value.trim().isEmpty())
            throw new IllegalArgumentException(field + " must not be blank");
        return value.trim();
    }
}
