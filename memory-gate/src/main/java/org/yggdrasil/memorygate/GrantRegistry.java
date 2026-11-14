package org.yggdrasil.memorygate;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * Private, durable grant registry. Tokens remain opaque and are bound to the
 * approved Android UID. SQLite commit must succeed before issuance is reported.
 */
final class GrantRegistry extends SQLiteOpenHelper {
    private static final String DATABASE_NAME = "memory-gate-grants.db";
    private static final int DATABASE_VERSION = 1;

    GrantRegistry(Context context) {
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
        db.execSQL("CREATE TABLE grants ("
                + "token TEXT PRIMARY KEY NOT NULL, "
                + "owner_uid INTEGER NOT NULL, "
                + "request_id TEXT NOT NULL, "
                + "objective TEXT NOT NULL, "
                + "purpose TEXT NOT NULL, "
                + "record_ids TEXT NOT NULL, "
                + "expires_at INTEGER NOT NULL, "
                + "stop_at INTEGER NOT NULL)");
        db.execSQL("CREATE INDEX grants_owner_uid ON grants(owner_uid)");
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        throw new IllegalStateException("No implicit grant-database migration is permitted.");
    }

    synchronized String issueForApprovedCaller(int ownerUid, MemoryAccessGrant grant) {
        if (ownerUid < 0 || grant == null || grant.permittedRecordIds.isEmpty()) return null;
        long now = System.currentTimeMillis();
        String firstRecord = grant.permittedRecordIds.iterator().next();
        if (AuthorizationDecision.evaluate(grant, firstRecord, now)
                != AuthorizationDecision.Outcome.ALLOW) return null;

        String token = UUID.randomUUID().toString();
        ContentValues values = new ContentValues();
        values.put("token", token);
        values.put("owner_uid", ownerUid);
        values.put("request_id", grant.requestId);
        values.put("objective", grant.objective);
        values.put("purpose", grant.purpose);
        values.put("record_ids", encodeRecordIds(grant.permittedRecordIds));
        values.put("expires_at", grant.expiresAtMillis);
        values.put("stop_at", grant.stopAtMillis);

        try {
            if (getWritableDatabase().insertOrThrow("grants", null, values) < 0) return null;
            return token;
        } catch (RuntimeException failure) {
            return null;
        }
    }

    synchronized MemoryAccessGrant resolve(
            String token, int callingUid, String requestedRecordId, long nowMillis) {
        if (token == null || callingUid < 0 || requestedRecordId == null) return null;
        SQLiteDatabase db;
        try {
            db = getReadableDatabase();
        } catch (RuntimeException failure) {
            return null;
        }
        try (Cursor cursor = db.query("grants",
                new String[]{"owner_uid", "request_id", "objective", "purpose",
                        "record_ids", "expires_at", "stop_at"},
                "token = ?", new String[]{token}, null, null, null, "1")) {
            if (!cursor.moveToFirst() || cursor.getInt(0) != callingUid) return null;
            MemoryAccessGrant grant = readGrant(cursor);
            if (AuthorizationDecision.evaluate(grant, requestedRecordId, nowMillis)
                    != AuthorizationDecision.Outcome.ALLOW) {
                deleteToken(db, token);
                return null;
            }
            return grant;
        } catch (RuntimeException failure) {
            return null;
        }
    }

    synchronized String claimForUid(int callingUid, long nowMillis) {
        if (callingUid < 0) return null;
        SQLiteDatabase db;
        try {
            db = getWritableDatabase();
        } catch (RuntimeException failure) {
            return null;
        }
        String match = null;
        try (Cursor cursor = db.query("grants",
                new String[]{"token", "owner_uid", "request_id", "objective", "purpose",
                        "record_ids", "expires_at", "stop_at"},
                "owner_uid = ?", new String[]{Integer.toString(callingUid)},
                null, null, null)) {
            while (cursor.moveToNext()) {
                MemoryAccessGrant grant = readGrant(cursor, 1);
                if (AuthorizationDecision.evaluate(grant,
                        grant.permittedRecordIds.iterator().next(), nowMillis)
                        != AuthorizationDecision.Outcome.ALLOW) {
                    deleteToken(db, cursor.getString(0));
                    continue;
                }
                if (match != null) return null; // Ambiguous grants fail closed.
                match = cursor.getString(0);
            }
            return match;
        } catch (RuntimeException failure) {
            return null;
        }
    }

    synchronized void revoke(String token) {
        if (token == null) return;
        try {
            deleteToken(getWritableDatabase(), token);
        } catch (RuntimeException ignored) {
            // A failed revocation is not reported as successful; expiry remains the backstop.
        }
    }

    private static MemoryAccessGrant readGrant(Cursor cursor) {
        return readGrant(cursor, 0);
    }

    private static MemoryAccessGrant readGrant(Cursor cursor, int offset) {
        Set<String> ids = decodeRecordIds(cursor.getString(offset + 4));
        return new MemoryAccessGrant(cursor.getString(offset + 1),
                cursor.getString(offset + 2), cursor.getString(offset + 3),
                ids, cursor.getLong(offset + 5), cursor.getLong(offset + 6));
    }

    private static String encodeRecordIds(Set<String> ids) {
        StringBuilder encoded = new StringBuilder();
        for (String id : ids) {
            if (id == null || id.isEmpty() || id.indexOf('\n') >= 0) {
                throw new IllegalArgumentException("Invalid record identifier");
            }
            if (encoded.length() > 0) encoded.append('\n');
            encoded.append(id);
        }
        return encoded.toString();
    }

    private static Set<String> decodeRecordIds(String encoded) {
        if (encoded == null || encoded.isEmpty()) return Collections.emptySet();
        Set<String> ids = new HashSet<>();
        Collections.addAll(ids, encoded.split("\\n", -1));
        ids.remove("");
        return ids;
    }

    private static void deleteToken(SQLiteDatabase db, String token) {
        db.delete("grants", "token = ?", new String[]{token});
    }
}
