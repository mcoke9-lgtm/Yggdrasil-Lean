package org.yggdrasil.scheduler.builder;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import java.util.UUID;

/**
 * Canonical local identity store for the Builder-native runtime process.
 * It is deliberately separate from the Android UI's conversation and scheduler stores.
 */
public final class BuilderRuntimeStore extends SQLiteOpenHelper {
    public static final String DATABASE_NAME = "yggdrasil_builder_runtime.db";
    private static final int DATABASE_VERSION = 1;

    public BuilderRuntimeStore(Context context) {
        super(context.getApplicationContext(), DATABASE_NAME, null, DATABASE_VERSION);
    }

    @Override public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE runtime_identity (singleton_id INTEGER PRIMARY KEY CHECK(singleton_id=1), runtime_id TEXT NOT NULL UNIQUE, created_at_epoch_ms INTEGER NOT NULL)");
    }

    @Override public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        throw new IllegalStateException("No destructive Builder runtime identity migration is defined");
    }

    /** Creates the runtime identity once and returns the same identity after process restart. */
    public synchronized NodeIdentity getOrCreateIdentity() {
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            try (Cursor cursor = db.rawQuery(
                    "SELECT runtime_id,created_at_epoch_ms FROM runtime_identity WHERE singleton_id=1",
                    null)) {
                if (cursor.moveToFirst()) {
                    NodeIdentity identity = new NodeIdentity(cursor.getString(0), cursor.getLong(1));
                    db.setTransactionSuccessful();
                    return identity;
                }
            }
            NodeIdentity identity = new NodeIdentity(UUID.randomUUID().toString(),
                    System.currentTimeMillis());
            ContentValues values = new ContentValues();
            values.put("singleton_id", 1);
            values.put("runtime_id", identity.runtimeId);
            values.put("created_at_epoch_ms", identity.createdAtEpochMillis);
            db.insertOrThrow("runtime_identity", null, values);
            db.setTransactionSuccessful();
            return identity;
        } finally {
            db.endTransaction();
        }
    }

    public static final class NodeIdentity {
        public final String runtimeId;
        public final long createdAtEpochMillis;

        NodeIdentity(String runtimeId, long createdAtEpochMillis) {
            this.runtimeId = runtimeId;
            this.createdAtEpochMillis = createdAtEpochMillis;
        }
    }
}
