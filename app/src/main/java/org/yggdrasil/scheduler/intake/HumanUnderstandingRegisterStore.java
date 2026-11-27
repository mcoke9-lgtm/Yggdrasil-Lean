package org.yggdrasil.scheduler.intake;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * Android persistence for the YIM-001 Human Understanding Register.
 *
 * Technical IDs remain canonical. Naming proposals are append-only events.
 * This class intentionally has no adoption method: adoption must pass through
 * a separately authenticated human/Tyr authorization boundary.
 */
public final class HumanUnderstandingRegisterStore extends SQLiteOpenHelper {
    public static final String FORMAT = "YIM-001-Human-Understanding-Register";
    public static final int FORMAT_VERSION = 1;
    private static final String DB_NAME = "yim_human_understanding.db";
    private static final int DB_VERSION = 1;
    private final String configuredRegistryId;

    public HumanUnderstandingRegisterStore(Context context, String registryId) {
        super(context.getApplicationContext(), DB_NAME, null, DB_VERSION);
        if (blank(registryId)) throw new IllegalArgumentException("registryId is required");
        configuredRegistryId = registryId.trim();
    }

    @Override public void onConfigure(SQLiteDatabase db) {
        db.setForeignKeyConstraintsEnabled(true);
    }

    @Override public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE registry_meta (singleton_id INTEGER PRIMARY KEY CHECK(singleton_id=1), registry_id TEXT NOT NULL)");
        db.execSQL("CREATE TABLE entries (entry_id TEXT PRIMARY KEY, technical_id TEXT NOT NULL UNIQUE, purpose TEXT NOT NULL)");
        db.execSQL("CREATE TABLE proposals (proposal_id TEXT PRIMARY KEY, entry_id TEXT NOT NULL, event_id TEXT NOT NULL UNIQUE, payload_json TEXT NOT NULL, FOREIGN KEY(entry_id) REFERENCES entries(entry_id))");
        db.execSQL("CREATE TABLE events (sequence INTEGER PRIMARY KEY AUTOINCREMENT, event_id TEXT NOT NULL UNIQUE, event_type TEXT NOT NULL, actor_id TEXT NOT NULL, payload_json TEXT NOT NULL)");
        ContentValues meta = new ContentValues();
        meta.put("singleton_id", 1);
        meta.put("registry_id", configuredRegistryId);
        db.insertOrThrow("registry_meta", null, meta);
    }

    @Override public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        throw new IllegalStateException("No destructive YIM register migration is defined");
    }

    public synchronized void verifyRegistryIdentity() {
        try (Cursor c = getReadableDatabase().rawQuery(
                "SELECT registry_id FROM registry_meta WHERE singleton_id=1", null)) {
            if (!c.moveToFirst() || !configuredRegistryId.equals(c.getString(0)))
                throw new IllegalStateException("Configured registry ID differs from persisted registry identity");
        }
    }

    public synchronized JSONObject createEntry(String entryId, String technicalId, String purpose,
            String eventId, String actorId, JSONArray keywords, JSONArray preservedTerms) throws Exception {
        requireText(entryId, "entryId"); requireText(technicalId, "technicalId");
        requireText(purpose, "purpose"); requireText(eventId, "eventId"); requireText(actorId, "actorId");
        validateStringArray(keywords, "keywords"); validateStringArray(preservedTerms, "preservedTerms");
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            try (Cursor c = db.rawQuery("SELECT technical_id,purpose FROM entries WHERE entry_id=?",
                    new String[]{entryId})) {
                if (c.moveToFirst()) {
                    if (!technicalId.equals(c.getString(0)) || !purpose.equals(c.getString(1)))
                        throw new IllegalStateException("Existing entry identity conflicts with requested identity");
                    JSONObject original = findEvent(db, "entry.created", entryId);
                    if (!new JSONArray(keywords.toString()).toString().equals(original.getJSONArray("keywords").toString())
                            || !new JSONArray(preservedTerms.toString()).toString().equals(original.getJSONArray("preserved_terms").toString()))
                        throw new IllegalStateException("Existing entry metadata conflicts with requested metadata");
                    db.setTransactionSuccessful();
                    return original;
                }
            }
            ContentValues entry = new ContentValues();
            entry.put("entry_id", entryId); entry.put("technical_id", technicalId); entry.put("purpose", purpose);
            db.insertOrThrow("entries", null, entry);
            JSONObject payload = new JSONObject();
            payload.put("entry_id", entryId); payload.put("technical_id", technicalId); payload.put("purpose", purpose);
            payload.put("keywords", new JSONArray(keywords.toString()));
            payload.put("preserved_terms", new JSONArray(preservedTerms.toString()));
            appendEvent(db, eventId, "entry.created", actorId, payload);
            db.setTransactionSuccessful();
            return payload;
        } finally { db.endTransaction(); }
    }

    public synchronized JSONObject appendMariProposal(String entryId, String proposalId, String eventId,
            String actorId, String preferredOptionId, JSONArray options,
            JSONArray competingInterpretations, JSONArray sourceRefs) throws Exception {
        requireText(entryId, "entryId"); requireText(proposalId, "proposalId");
        requireText(eventId, "eventId"); requireText(actorId, "actorId");
        requireText(preferredOptionId, "preferredOptionId");
        validateOptions(options); validateStringArray(competingInterpretations, "competingInterpretations");
        validateStringArray(sourceRefs, "sourceRefs");
        boolean preferredFound = false;
        for (int i=0;i<options.length();i++)
            if (preferredOptionId.equals(options.getJSONObject(i).getString("option_id"))) preferredFound = true;
        if (!preferredFound) throw new IllegalArgumentException("Preferred option must identify a supplied alternative");

        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            if (!exists(db, "SELECT entry_id FROM entries WHERE entry_id=?", entryId))
                throw new IllegalArgumentException("Proposal references an unknown entry");
            JSONObject payload = new JSONObject();
            payload.put("entry_id", entryId); payload.put("proposal_id", proposalId);
            payload.put("proposer_role", "Mari"); payload.put("preferred_option_id", preferredOptionId);
            payload.put("options", new JSONArray(options.toString()));
            payload.put("competing_interpretations", new JSONArray(competingInterpretations.toString()));
            payload.put("source_refs", new JSONArray(sourceRefs.toString()));
            try (Cursor existing = db.rawQuery(
                    "SELECT entry_id,event_id,payload_json FROM proposals WHERE proposal_id=?",
                    new String[]{proposalId})) {
                if (existing.moveToFirst()) {
                    if (entryId.equals(existing.getString(0))
                            && eventId.equals(existing.getString(1))
                            && payload.toString().equals(existing.getString(2))) {
                        db.setTransactionSuccessful();
                        return payload;
                    }
                    throw new IllegalArgumentException("Proposal identity was reused with conflicting content");
                }
            }
            ContentValues proposal = new ContentValues();
            proposal.put("proposal_id", proposalId); proposal.put("entry_id", entryId);
            proposal.put("event_id", eventId); proposal.put("payload_json", payload.toString());
            db.insertOrThrow("proposals", null, proposal);
            appendEvent(db, eventId, "name.proposed", actorId, payload);
            db.setTransactionSuccessful();
            return payload;
        } finally { db.endTransaction(); }
    }

    /** Portable snapshot uses the same event envelope and field names as the Python YIM-001 register. */
    public synchronized JSONObject exportSnapshot() throws JSONException {
        verifyRegistryIdentity();
        JSONObject snapshot = new JSONObject();
        snapshot.put("format", FORMAT); snapshot.put("format_version", FORMAT_VERSION);
        snapshot.put("registry_id", configuredRegistryId);
        JSONArray events = new JSONArray();
        try (Cursor c = getReadableDatabase().rawQuery(
                "SELECT sequence,event_id,event_type,actor_id,payload_json FROM events ORDER BY sequence", null)) {
            while (c.moveToNext()) {
                JSONObject event = new JSONObject();
                event.put("sequence", c.getLong(0)); event.put("event_id", c.getString(1));
                event.put("event_type", c.getString(2)); event.put("actor_id", c.getString(3));
                event.put("payload", new JSONObject(c.getString(4))); events.put(event);
            }
        }
        snapshot.put("events", events);
        return snapshot;
    }

    public synchronized int eventCount() {
        try (Cursor c = getReadableDatabase().rawQuery("SELECT COUNT(*) FROM events", null)) {
            return c.moveToFirst() ? c.getInt(0) : 0;
        }
    }

    public synchronized boolean hasProposal(String entryId) {
        try (Cursor c = getReadableDatabase().rawQuery(
                "SELECT proposal_id FROM proposals WHERE entry_id=? LIMIT 1",
                new String[]{entryId})) {
            return c.moveToFirst();
        }
    }

    public synchronized void requireEntryIdentity(String entryId, String technicalId, String purpose) {
        requireText(entryId, "entryId"); requireText(technicalId, "technicalId");
        requireText(purpose, "purpose");
        try (Cursor c = getReadableDatabase().rawQuery(
                "SELECT technical_id,purpose FROM entries WHERE entry_id=?",
                new String[]{entryId})) {
            if (!c.moveToFirst() || !technicalId.equals(c.getString(0)) || !purpose.equals(c.getString(1)))
                throw new IllegalArgumentException("Proposal request does not match the registered technical identity");
        }
    }

    private static void appendEvent(SQLiteDatabase db, String id, String type, String actor, JSONObject payload) {
        ContentValues event = new ContentValues();
        event.put("event_id", id); event.put("event_type", type);
        event.put("actor_id", actor); event.put("payload_json", payload.toString());
        db.insertOrThrow("events", null, event);
    }

    private static JSONObject findEvent(SQLiteDatabase db, String type, String entryId) throws JSONException {
        try (Cursor c = db.rawQuery("SELECT payload_json FROM events WHERE event_type=? ORDER BY sequence",
                new String[]{type})) {
            while (c.moveToNext()) {
                JSONObject payload = new JSONObject(c.getString(0));
                if (entryId.equals(payload.optString("entry_id"))) return payload;
            }
        }
        throw new IllegalStateException("Entry index has no corresponding append-only creation event");
    }

    private static boolean exists(SQLiteDatabase db, String sql, String value) {
        try (Cursor c = db.rawQuery(sql, new String[]{value})) { return c.moveToFirst(); }
    }

    private static void validateOptions(JSONArray options) throws JSONException {
        if (options == null || options.length() < 2)
            throw new IllegalArgumentException("Mari must preserve at least two naming alternatives");
        java.util.HashSet<String> ids = new java.util.HashSet<>();
        for (int i=0;i<options.length();i++) {
            JSONObject o = options.getJSONObject(i);
            if (o.length()!=5 || !o.has("option_id") || !o.has("human_name") || !o.has("meaning")
                    || !o.has("strengths") || !o.has("limitations"))
                throw new IllegalArgumentException("Each option needs option_id, human_name, meaning, strengths, limitations");
            requireText(o.getString("option_id"), "option_id");
            requireText(o.getString("human_name"), "human_name");
            requireText(o.getString("meaning"), "meaning");
            if (!ids.add(o.getString("option_id"))) throw new IllegalArgumentException("Duplicate option_id");
            validateStringArray(o.getJSONArray("strengths"), "strengths");
            validateStringArray(o.getJSONArray("limitations"), "limitations");
        }
    }

    private static void validateStringArray(JSONArray values, String field) throws JSONException {
        if (values == null) throw new IllegalArgumentException(field + " must be an array");
        for (int i=0;i<values.length();i++) {
            Object item=values.get(i);
            if (!(item instanceof String))
                throw new IllegalArgumentException(field + "[" + i + "] must be text");
            requireText((String)item, field + "[" + i + "]");
        }
    }

    private static void requireText(String value, String field) {
        if (blank(value)) throw new IllegalArgumentException(field + " must be non-empty text");
    }
    private static boolean blank(String value) { return value == null || value.trim().isEmpty(); }
}
