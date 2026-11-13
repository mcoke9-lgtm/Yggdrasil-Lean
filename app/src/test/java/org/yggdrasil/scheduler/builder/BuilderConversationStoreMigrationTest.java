package org.yggdrasil.scheduler.builder;

import android.content.ContentValues;
import android.content.Context;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

import java.util.List;

import static org.junit.Assert.*;

/** Verifies additive upgrade from the pre-assignment-link journal schema. */
@RunWith(RobolectricTestRunner.class)
public class BuilderConversationStoreMigrationTest {
    private static final String DATABASE = "builder-conversation-v1-migration-test.db";

    private static final class LegacyV1Helper extends SQLiteOpenHelper {
        LegacyV1Helper(Context context) { super(context, DATABASE, null, 1); }

        @Override public void onCreate(SQLiteDatabase db) {
            db.execSQL("CREATE TABLE conversation_turns ("
                    + "_id INTEGER PRIMARY KEY AUTOINCREMENT, "
                    + "thread_id TEXT NOT NULL, turn_id TEXT NOT NULL, "
                    + "protocol_version INTEGER NOT NULL, speaker TEXT NOT NULL, "
                    + "recipient TEXT, reply_to_turn_id TEXT, turn_type TEXT NOT NULL, "
                    + "evidence_status TEXT NOT NULL, timestamp_epoch_millis INTEGER NOT NULL, "
                    + "body TEXT NOT NULL, UNIQUE(thread_id, turn_id))");
            db.execSQL("CREATE INDEX conversation_thread_order ON conversation_turns(thread_id, _id)");
        }

        @Override public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
            throw new AssertionError("Legacy fixture must remain schema version 1");
        }
    }

    @Test public void v1HistorySurvivesUpgradeWithNullAssignmentId() {
        Context context = RuntimeEnvironment.getApplication();
        context.deleteDatabase(DATABASE);

        LegacyV1Helper legacy = new LegacyV1Helper(context);
        SQLiteDatabase db = legacy.getWritableDatabase();
        ContentValues row = new ContentValues();
        row.put("thread_id", "legacy-thread");
        row.put("turn_id", "legacy-turn-1");
        row.put("protocol_version", BuilderConversationProtocol.LEGACY_VERSION);
        row.put("speaker", BuilderConversationProtocol.Participant.USER.name());
        row.putNull("recipient");
        row.putNull("reply_to_turn_id");
        row.put("turn_type", BuilderConversationProtocol.TurnType.CONTRIBUTION.name());
        row.put("evidence_status", BuilderConversationProtocol.EvidenceStatus.USER_CONFIRMED.name());
        row.put("timestamp_epoch_millis", 1234L);
        row.put("body", "Preserve this pre-MDM conversation.");
        assertTrue(db.insertOrThrow("conversation_turns", null, row) > 0);
        legacy.close();

        BuilderConversationStore upgraded = new BuilderConversationStore(context, DATABASE);
        try {
            List<BuilderConversationProtocol.Turn> turns = upgraded.readThread("legacy-thread");
            assertEquals(1, turns.size());
            assertEquals("legacy-turn-1", turns.get(0).turnId);
            assertEquals("Preserve this pre-MDM conversation.", turns.get(0).body);
            assertNull(turns.get(0).assignmentId);
            assertEquals(1, upgraded.exportSnapshot().getInt("turn_count"));
        } catch (org.json.JSONException failure) {
            throw new AssertionError(failure);
        } finally {
            upgraded.close();
            context.deleteDatabase(DATABASE);
        }
    }
}
