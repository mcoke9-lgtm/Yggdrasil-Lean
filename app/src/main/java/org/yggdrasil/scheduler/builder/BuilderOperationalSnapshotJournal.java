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
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Append-only provenance journal for the exact operational snapshot used by a Builder round.
 * Stores a digest and bounded provenance metadata, not credentials or the full prompt snapshot.
 */
public final class BuilderOperationalSnapshotJournal extends SQLiteOpenHelper {
    private static final String DB_NAME = "builder-operational-snapshots.db";
    private static final int DB_VERSION = 1;
    private static final int MAX_SNAPSHOT_CHARS = 256 * 1024;

    public BuilderOperationalSnapshotJournal(Context context) {
        super(context.getApplicationContext(), DB_NAME, null, DB_VERSION);
    }

    @Override public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE operational_snapshot_events ("
                + "_id INTEGER PRIMARY KEY AUTOINCREMENT, "
                + "event_id TEXT NOT NULL UNIQUE, thread_id TEXT NOT NULL, "
                + "round_started_at_epoch_ms INTEGER NOT NULL, "
                + "register_schema TEXT NOT NULL, register_schema_version TEXT NOT NULL, "
                + "register_status TEXT NOT NULL, snapshot_sha256 TEXT NOT NULL, "
                + "source TEXT NOT NULL, evaluated_on TEXT, selected_builders_json TEXT NOT NULL, "
                + "evidence_refs_json TEXT NOT NULL)");
    }

    @Override public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        throw new IllegalStateException("No automatic destructive operational snapshot migration is defined");
    }

    /** Persists provenance before any provider request; failure must prevent dispatch. */
    public synchronized String append(String threadId, String snapshotJson,
                                      List<BuilderRole> selectedRoles, long timestampMs) {
        if (threadId == null || threadId.trim().isEmpty())
            throw new IllegalArgumentException("threadId is required");
        if (snapshotJson == null || snapshotJson.length() > MAX_SNAPSHOT_CHARS)
            throw new IllegalArgumentException("Snapshot is missing or exceeds the size limit");
        if (selectedRoles == null || selectedRoles.isEmpty())
            throw new IllegalArgumentException("Selected Builder roles are required");
        if (timestampMs <= 0) throw new IllegalArgumentException("Timestamp must be positive");
        try {
            JSONObject snapshot = new JSONObject(snapshotJson);
            JSONArray builders = new JSONArray();
            for (BuilderRole role : selectedRoles) {
                if (role == null) throw new IllegalArgumentException("Builder role cannot be null");
                builders.put(role.name());
            }
            JSONArray evidence = new JSONArray();
            JSONArray constraints = snapshot.optJSONArray("constraints");
            if (constraints != null) {
                for (int i = 0; i < constraints.length(); i++) {
                    JSONObject item = constraints.optJSONObject(i);
                    if (item == null) continue;
                    JSONObject ref = new JSONObject();
                    ref.put("constraint_id", item.optString("constraint_id", ""));
                    ref.put("capability", item.optString("capability", ""));
                    ref.put("observed_at", item.opt("observed_at") == null
                            ? JSONObject.NULL : item.opt("observed_at"));
                    ref.put("evidence_source", item.opt("evidence_source") == null
                            ? JSONObject.NULL : item.opt("evidence_source"));
                    ref.put("gate_decision", item.optString("gate_decision", "RECHECK_REQUIRED"));
                    evidence.put(ref);
                }
            }
            String digest = sha256(snapshotJson);
            String eventId = UUID.randomUUID().toString();
            ContentValues values = new ContentValues();
            values.put("event_id", eventId);
            values.put("thread_id", threadId.trim());
            values.put("round_started_at_epoch_ms", timestampMs);
            values.put("register_schema", snapshot.optString("schema", "unknown"));
            values.put("register_schema_version", snapshot.optString("schema_version", "unknown"));
            values.put("register_status", snapshot.optString("register_status", "UNAVAILABLE"));
            values.put("snapshot_sha256", digest);
            values.put("source", "bundled_asset:operational-limits.json");
            JSONObject evaluation = snapshot.optJSONObject("runtime_evaluation");
            values.put("evaluated_on", evaluation == null ? null : evaluation.optString("evaluated_on", null));
            values.put("selected_builders_json", builders.toString());
            values.put("evidence_refs_json", evidence.toString());
            getWritableDatabase().insertOrThrow("operational_snapshot_events", null, values);
            return eventId;
        } catch (org.json.JSONException e) {
            throw new IllegalArgumentException("Operational snapshot is not valid JSON", e);
        }
    }

    /** Export metadata for audit/recovery; snapshot contents and secrets are not copied. */
    public synchronized JSONArray exportEvents() {
        JSONArray rows = new JSONArray();
        try (Cursor c = getReadableDatabase().rawQuery(
                "SELECT event_id,thread_id,round_started_at_epoch_ms,register_schema,"
                + "register_schema_version,register_status,snapshot_sha256,source,evaluated_on,"
                + "selected_builders_json,evidence_refs_json FROM operational_snapshot_events ORDER BY _id ASC",
                null)) {
            while (c.moveToNext()) {
                JSONObject row = new JSONObject();
                row.put("event_id", c.getString(0));
                row.put("thread_id", c.getString(1));
                row.put("round_started_at_epoch_ms", c.getLong(2));
                row.put("register_schema", c.getString(3));
                row.put("register_schema_version", c.getString(4));
                row.put("register_status", c.getString(5));
                row.put("snapshot_sha256", c.getString(6));
                row.put("source", c.getString(7));
                row.put("evaluated_on", c.isNull(8) ? JSONObject.NULL : c.getString(8));
                row.put("selected_builders", new JSONArray(c.getString(9)));
                row.put("evidence_refs", new JSONArray(c.getString(10)));
                rows.put(row);
            }
            return rows;
        } catch (Exception e) {
            throw new IllegalStateException("Could not export operational snapshot provenance", e);
        }
    }

    private static String sha256(String value) {
        try {
            byte[] bytes = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder();
            for (byte b : bytes) result.append(String.format(Locale.ROOT, "%02x", b & 0xff));
            return result.toString();
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }
}
