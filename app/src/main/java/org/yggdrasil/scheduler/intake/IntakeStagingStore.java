package org.yggdrasil.scheduler.intake;

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
import java.util.Locale;

/** Private-device staging store. This is deliberately not the canonical YIM audited Roots store. */
public final class IntakeStagingStore extends SQLiteOpenHelper {
    private static final String DB_NAME = "yim_intake_staging.db";
    private static final int DB_VERSION = 1;
    private static final String PACKETS = "staged_packets";
    private static final String SOURCES = "staged_sources";

    public IntakeStagingStore(Context context) { super(context.getApplicationContext(), DB_NAME, null, DB_VERSION); }

    @Override public void onConfigure(SQLiteDatabase db) {
        db.setForeignKeyConstraintsEnabled(true);
    }

    @Override public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE " + SOURCES + " ("
                + "source_sha256 TEXT PRIMARY KEY, queue_id TEXT NOT NULL, "
                + "source_queue_json TEXT NOT NULL, staged_at_ms INTEGER NOT NULL)");
        db.execSQL("CREATE TABLE " + PACKETS + " ("
                + "queue_id TEXT NOT NULL, packet_id TEXT NOT NULL, captured_at TEXT NOT NULL, "
                + "source_kind TEXT NOT NULL, language TEXT NOT NULL, placement_status TEXT NOT NULL, "
                + "packet_json TEXT NOT NULL, source_sha256 TEXT NOT NULL, staged_at_ms INTEGER NOT NULL, "
                + "PRIMARY KEY(queue_id, packet_id), "
                + "FOREIGN KEY(source_sha256) REFERENCES " + SOURCES + "(source_sha256))");
    }

    @Override public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        throw new IllegalStateException("No automatic destructive intake staging migration is defined");
    }

    /** Atomically records one source file and stages all its packets without duplicating the file per packet. */
    public synchronized StageResult stage(IntakeQueueValidator.ValidatedQueue queue) throws Exception {
        if (queue == null) throw new IllegalArgumentException("Validated queue is required");
        JSONArray packets = queue.copyRoot().getJSONArray("packets");
        String sourceDigest = sha256(queue.rawJson);
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        int inserted = 0, alreadyPresent = 0;
        try {
            try (Cursor c = db.rawQuery("SELECT source_queue_json FROM " + SOURCES
                    + " WHERE source_sha256=?", new String[]{sourceDigest})) {
                if (c.moveToFirst()) {
                    if (!queue.rawJson.equals(c.getString(0)))
                        throw new IllegalStateException("Source digest collision detected");
                } else {
                    ContentValues source = new ContentValues();
                    source.put("source_sha256", sourceDigest);
                    source.put("queue_id", queue.queueId);
                    source.put("source_queue_json", queue.rawJson);
                    source.put("staged_at_ms", System.currentTimeMillis());
                    db.insertOrThrow(SOURCES, null, source);
                }
            }

            for (int i = 0; i < packets.length(); i++) {
                JSONObject packet = packets.getJSONObject(i);
                String packetId = packet.getString("packet_id");
                String packetJson = packet.toString();
                try (Cursor c = db.rawQuery("SELECT packet_json FROM " + PACKETS
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
                row.put("source_sha256", sourceDigest);
                row.put("staged_at_ms", System.currentTimeMillis());
                db.insertOrThrow(PACKETS, null, row);
                inserted++;
            }
            db.setTransactionSuccessful();
        } finally { db.endTransaction(); }
        return new StageResult(inserted, alreadyPresent, queue.packetCount, queue.pendingCount);
    }

    public synchronized List<StagedPacket> listPending() {
        List<StagedPacket> result = new ArrayList<>();
        String sql = "SELECT p.queue_id,p.packet_id,p.captured_at,p.source_kind,p.language,"
                + "p.placement_status,p.packet_json,s.source_queue_json,p.staged_at_ms "
                + "FROM " + PACKETS + " p JOIN " + SOURCES + " s "
                + "ON p.source_sha256=s.source_sha256 "
                + "WHERE p.placement_status='awaiting_placement' "
                + "ORDER BY p.staged_at_ms,p.queue_id,p.packet_id";
        try (Cursor c = getReadableDatabase().rawQuery(sql, null)) {
            while (c.moveToNext()) result.add(new StagedPacket(c.getString(0), c.getString(1),
                    c.getString(2), c.getString(3), c.getString(4), c.getString(5),
                    c.getString(6), c.getString(7), c.getLong(8)));
        }
        return Collections.unmodifiableList(result);
    }

    public synchronized int countPending() {
        try (Cursor c = getReadableDatabase().rawQuery("SELECT COUNT(*) FROM " + PACKETS
                + " WHERE placement_status='awaiting_placement'", null)) {
            return c.moveToFirst() ? c.getInt(0) : 0;
        }
    }

    private static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder out = new StringBuilder();
            for (byte b : digest) out.append(String.format(Locale.ROOT, "%02x", b & 0xff));
            return out.toString();
        } catch (Exception e) { throw new IllegalStateException("SHA-256 unavailable", e); }
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
