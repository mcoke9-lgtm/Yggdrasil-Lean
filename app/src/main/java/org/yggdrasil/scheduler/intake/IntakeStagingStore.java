package org.yggdrasil.scheduler.intake;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Private-device staging store. This is deliberately not the canonical YIM audited Roots store. */
public final class IntakeStagingStore extends SQLiteOpenHelper {
    private static final String DB_NAME = "yim_intake_staging.db";
    private static final int DB_VERSION = 1;
    private static final String TABLE = "staged_packets";

    public IntakeStagingStore(Context context) { super(context.getApplicationContext(), DB_NAME, null, DB_VERSION); }

    @Override public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE " + TABLE + " ("
                + "queue_id TEXT NOT NULL, packet_id TEXT NOT NULL, captured_at TEXT NOT NULL, "
                + "source_kind TEXT NOT NULL, language TEXT NOT NULL, placement_status TEXT NOT NULL, "
                + "packet_json TEXT NOT NULL, source_queue_json TEXT NOT NULL, staged_at_ms INTEGER NOT NULL, "
                + "PRIMARY KEY(queue_id, packet_id))");
    }

    @Override public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        throw new IllegalStateException("No automatic destructive intake staging migration is defined");
    }

    /** Atomically stages every packet; exact submitted queue text is retained in each row. */
    public synchronized StageResult stage(IntakeQueueValidator.ValidatedQueue queue) throws Exception {
        if (queue == null) throw new IllegalArgumentException("Validated queue is required");
        JSONArray packets = queue.copyRoot().getJSONArray("packets");
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        int inserted = 0, alreadyPresent = 0;
        try {
            for (int i = 0; i < packets.length(); i++) {
                JSONObject packet = packets.getJSONObject(i);
                String packetId = packet.getString("packet_id");
                String packetJson = packet.toString();
                try (Cursor c = db.rawQuery("SELECT packet_json FROM " + TABLE
                        + " WHERE queue_id=? AND packet_id=?",
                        new String[]{queue.queueId, packetId})) {
                    if (c.moveToFirst()) {
                        if (!packetJson.equals(c.getString(0)))
                            throw new IllegalStateException("Packet identity already exists with different content: " + packetId);
                        alreadyPresent++;
                        continue;
                    }
                }
                ContentValues row = new ContentValues();
                row.put("queue_id", queue.queueId);
                row.put("packet_id", packetId);
                row.put("captured_at", packet.getString("captured_at"));
                row.put("source_kind", packet.getString("source_kind"));
                row.put("language", packet.getString("language"));
                row.put("placement_status", packet.getString("placement_status"));
                row.put("packet_json", packetJson);
                row.put("source_queue_json", queue.rawJson);
                row.put("staged_at_ms", System.currentTimeMillis());
                db.insertOrThrow(TABLE, null, row);
                inserted++;
            }
            db.setTransactionSuccessful();
        } finally { db.endTransaction(); }
        return new StageResult(inserted, alreadyPresent, queue.packetCount, queue.pendingCount);
    }

    public synchronized List<StagedPacket> listPending() {
        List<StagedPacket> result = new ArrayList<>();
        try (Cursor c = getReadableDatabase().rawQuery("SELECT queue_id,packet_id,captured_at,source_kind,language,placement_status,packet_json,source_queue_json,staged_at_ms FROM "
                + TABLE + " WHERE placement_status='awaiting_placement' ORDER BY staged_at_ms,queue_id,packet_id", null)) {
            while (c.moveToNext()) result.add(new StagedPacket(c.getString(0), c.getString(1),
                    c.getString(2), c.getString(3), c.getString(4), c.getString(5),
                    c.getString(6), c.getString(7), c.getLong(8)));
        }
        return Collections.unmodifiableList(result);
    }

    public synchronized int countPending() {
        try (Cursor c = getReadableDatabase().rawQuery("SELECT COUNT(*) FROM " + TABLE
                + " WHERE placement_status='awaiting_placement'", null)) {
            return c.moveToFirst() ? c.getInt(0) : 0;
        }
    }

    public static final class StageResult {
        public final int inserted, alreadyPresent, total, pending;
        StageResult(int inserted, int alreadyPresent, int total, int pending) {
            this.inserted=inserted; this.alreadyPresent=alreadyPresent; this.total=total; this.pending=pending;
        }
    }

    public static final class StagedPacket {
        public final String queueId, packetId, capturedAt, sourceKind, language, placementStatus, packetJson, sourceQueueJson;
        public final long stagedAtMs;
        StagedPacket(String queueId, String packetId, String capturedAt, String sourceKind, String language,
                     String placementStatus, String packetJson, String sourceQueueJson, long stagedAtMs) {
            this.queueId=queueId; this.packetId=packetId; this.capturedAt=capturedAt;
            this.sourceKind=sourceKind; this.language=language; this.placementStatus=placementStatus;
            this.packetJson=packetJson; this.sourceQueueJson=sourceQueueJson; this.stagedAtMs=stagedAtMs;
        }
    }
}
