package org.yggdrasil.scheduler.builder;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Durable assignment ledger for MDM-001.
 *
 * This store records work contracts and recovery/return metadata. It does not
 * dispatch workers, grant permissions, or claim that a Character is executing.
 */
public final class CharacterAssignmentStore extends SQLiteOpenHelper {
    public static final String DATABASE_NAME = "yggdrasil_character_assignments.db";
    private static final int DATABASE_VERSION = 2;
    public static final String PAYLOAD_CHECKPOINT = "CHECKPOINT";
    public static final String PAYLOAD_RETURN_REPORT = "RETURN_REPORT";

    public enum Status {
        CREATED, AUTHORIZED, QUEUED, ACCEPTED, RUNNING, CHECKPOINTED,
        BLOCKED, INTERRUPTED, RETURNED, VERIFIED, CLOSED
    }

    public static final class Assignment {
        public final String assignmentId;
        public final String character;
        public final String requester;
        public final String objective;
        public final String scope;
        public final String permissionsJson;
        public final String constraintsJson;
        public final String evidenceRequirements;
        public final String checkpointJson;
        public final String returnReportJson;
        public final Status status;
        public final long createdAt;
        public final long updatedAt;

        Assignment(Cursor c) {
            assignmentId=c.getString(0); character=c.getString(1); requester=c.getString(2);
            objective=c.getString(3); scope=c.getString(4); permissionsJson=c.getString(5);
            constraintsJson=c.getString(6); evidenceRequirements=c.getString(7);
            checkpointJson=c.getString(8); returnReportJson=c.getString(9);
            status=Status.valueOf(c.getString(10)); createdAt=c.getLong(11); updatedAt=c.getLong(12);
        }
    }

    /** Immutable version of a checkpoint or returned report. */
    public static final class Payload {
        public final long payloadId;
        public final String assignmentId;
        public final String payloadType;
        public final String payloadJson;
        public final long createdAt;

        Payload(Cursor c) {
            payloadId=c.getLong(0); assignmentId=c.getString(1);
            payloadType=c.getString(2); payloadJson=c.getString(3);
            createdAt=c.getLong(4);
        }
    }

    private final Context appContext;

    public CharacterAssignmentStore(Context context) {
        super(context.getApplicationContext(), DATABASE_NAME, null, DATABASE_VERSION);
        appContext = context.getApplicationContext();
    }

    @Override public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE assignments (" +
                "assignment_id TEXT PRIMARY KEY, character TEXT NOT NULL, requester TEXT NOT NULL, " +
                "objective TEXT NOT NULL, scope TEXT NOT NULL, permissions_json TEXT NOT NULL, " +
                "constraints_json TEXT NOT NULL, evidence_requirements TEXT NOT NULL, " +
                "checkpoint_json TEXT, return_report_json TEXT, status TEXT NOT NULL, " +
                "created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL)");
        db.execSQL("CREATE INDEX assignments_status_updated ON assignments(status, updated_at)");
        db.execSQL("CREATE INDEX assignments_character_updated ON assignments(character, updated_at)");
        db.execSQL("CREATE TABLE assignment_events (" +
                "event_id INTEGER PRIMARY KEY AUTOINCREMENT, assignment_id TEXT NOT NULL, " +
                "from_status TEXT, to_status TEXT NOT NULL, event_type TEXT NOT NULL, " +
                "detail TEXT NOT NULL, created_at INTEGER NOT NULL)");
        createPayloadTable(db);
        createAppendOnlyTriggers(db);
    }

    private static void createPayloadTable(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE assignment_payloads (" +
                "payload_id INTEGER PRIMARY KEY AUTOINCREMENT, assignment_id TEXT NOT NULL, " +
                "payload_type TEXT NOT NULL CHECK(payload_type IN ('CHECKPOINT','RETURN_REPORT')), " +
                "payload_json TEXT NOT NULL, created_at INTEGER NOT NULL)");
        db.execSQL("CREATE INDEX assignment_payloads_assignment ON assignment_payloads(assignment_id, payload_id)");
    }

    private static void createAppendOnlyTriggers(SQLiteDatabase db) {
        db.execSQL("CREATE TRIGGER assignment_events_no_update BEFORE UPDATE ON assignment_events " +
                "BEGIN SELECT RAISE(ABORT, 'assignment_events is append-only'); END");
        db.execSQL("CREATE TRIGGER assignment_events_no_delete BEFORE DELETE ON assignment_events " +
                "BEGIN SELECT RAISE(ABORT, 'assignment_events is append-only'); END");
        db.execSQL("CREATE TRIGGER assignment_payloads_no_update BEFORE UPDATE ON assignment_payloads " +
                "BEGIN SELECT RAISE(ABORT, 'assignment_payloads is append-only'); END");
        db.execSQL("CREATE TRIGGER assignment_payloads_no_delete BEFORE DELETE ON assignment_payloads " +
                "BEGIN SELECT RAISE(ABORT, 'assignment_payloads is append-only'); END");
    }

    @Override public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        if (oldVersion < 2) {
            db.execSQL("ALTER TABLE assignment_events ADD COLUMN event_type TEXT NOT NULL DEFAULT 'LIFECYCLE'");
            createPayloadTable(db);
            db.execSQL("INSERT INTO assignment_payloads(assignment_id,payload_type,payload_json,created_at) " +
                    "SELECT assignment_id,'CHECKPOINT',checkpoint_json,updated_at FROM assignments " +
                    "WHERE checkpoint_json IS NOT NULL");
            db.execSQL("INSERT INTO assignment_payloads(assignment_id,payload_type,payload_json,created_at) " +
                    "SELECT assignment_id,'RETURN_REPORT',return_report_json,updated_at FROM assignments " +
                    "WHERE return_report_json IS NOT NULL");
            createAppendOnlyTriggers(db);
        }
    }

    public synchronized void create(String id, String character, String requester,
            String objective, String scope, String permissionsJson, String constraintsJson,
            String evidenceRequirements) {
        requireText(id, "assignmentId"); requireText(requester, "requester");
        requireText(objective, "objective"); requireText(scope, "scope");
        requireText(evidenceRequirements, "evidenceRequirements");
        validateCharacter(character);
        requireText(permissionsJson, "permissionsJson"); requireText(constraintsJson, "constraintsJson");
        long now=System.currentTimeMillis();
        ContentValues v=new ContentValues();
        v.put("assignment_id",id); v.put("character",character); v.put("requester",requester);
        v.put("objective",objective); v.put("scope",scope); v.put("permissions_json",permissionsJson);
        v.put("constraints_json",constraintsJson); v.put("evidence_requirements",evidenceRequirements);
        v.put("status",Status.CREATED.name()); v.put("created_at",now); v.put("updated_at",now);
        SQLiteDatabase db=getWritableDatabase(); db.beginTransaction();
        try {
            db.insertOrThrow("assignments",null,v);
            event(db,id,null,Status.CREATED,"LIFECYCLE","Assignment recorded");
            db.setTransactionSuccessful();
        } finally { db.endTransaction(); }
    }

    public synchronized Assignment get(String id) {
        try (Cursor c=getReadableDatabase().query("assignments",null,"assignment_id=?",
                new String[]{id},null,null,null)) {
            return c.moveToFirst()?new Assignment(c):null;
        }
    }

    public synchronized List<Assignment> listOpen() {
        List<Assignment> out=new ArrayList<>();
        String closed=Status.CLOSED.name();
        try (Cursor c=getReadableDatabase().query("assignments",null,"status<>?",
                new String[]{closed},null,null,"updated_at ASC")) {
            while(c.moveToNext()) out.add(new Assignment(c));
        }
        return Collections.unmodifiableList(out);
    }

    public synchronized void transition(String id, Status next, String detail) {
        if(next==null) throw new IllegalArgumentException("next status is required");
        requireText(detail,"detail");
        SQLiteDatabase db=getWritableDatabase(); db.beginTransaction();
        try {
            Status current;
            try(Cursor c=db.rawQuery("SELECT status FROM assignments WHERE assignment_id=?",
                    new String[]{id})) {
                if(!c.moveToFirst()) throw new IllegalArgumentException("Unknown assignment: "+id);
                current=Status.valueOf(c.getString(0));
            }
            if(!allowed(current,next)) throw new IllegalStateException(
                    "Invalid assignment transition: "+current+" -> "+next);
            ContentValues v=new ContentValues(); v.put("status",next.name());
            v.put("updated_at",System.currentTimeMillis());
            db.update("assignments",v,"assignment_id=?",new String[]{id});
            event(db,id,current,next,"LIFECYCLE",detail); db.setTransactionSuccessful();
        } finally { db.endTransaction(); }
        if (next == Status.QUEUED) CharacterAssignmentWorkScheduler.enqueue(appContext);
    }

    /** Atomically claims the oldest authorized queued assignment for one dispatcher invocation. */
    public synchronized Assignment claimNextQueued() {
        SQLiteDatabase db=getWritableDatabase(); db.beginTransaction();
        try {
            String id=null;
            try (Cursor c=db.rawQuery("SELECT assignment_id FROM assignments WHERE status=? ORDER BY created_at ASC LIMIT 1",
                    new String[]{Status.QUEUED.name()})) {
                if (c.moveToFirst()) id=c.getString(0);
            }
            if (id==null) { db.setTransactionSuccessful(); return null; }
            ContentValues v=new ContentValues(); v.put("status",Status.ACCEPTED.name());
            v.put("updated_at",System.currentTimeMillis());
            int changed=db.update("assignments",v,"assignment_id=? AND status=?",
                    new String[]{id,Status.QUEUED.name()});
            if (changed!=1) { db.setTransactionSuccessful(); return null; }
            event(db,id,Status.QUEUED,Status.ACCEPTED,"LIFECYCLE","WorkManager dispatcher claimed assignment");
            Assignment claimed;
            try (Cursor c=db.query("assignments",null,"assignment_id=?",new String[]{id},null,null,null)) {
                claimed=c.moveToFirst()?new Assignment(c):null;
            }
            db.setTransactionSuccessful();
            return claimed;
        } finally { db.endTransaction(); }
    }

    public synchronized void checkpoint(String id, String checkpointJson) {
        requireText(checkpointJson,"checkpointJson");
        updatePayload(id,"checkpoint_json",checkpointJson,PAYLOAD_CHECKPOINT,"Checkpoint saved");
    }

    public synchronized void returnReport(String id, String reportJson) {
        requireText(reportJson,"reportJson");
        updatePayload(id,"return_report_json",reportJson,PAYLOAD_RETURN_REPORT,"Return report saved");
    }

    private void updatePayload(String id,String column,String payload,String payloadType,String detail) {
        SQLiteDatabase db=getWritableDatabase(); db.beginTransaction();
        try {
            Status current=Status.valueOf(statusOf(db,id));
            long now=System.currentTimeMillis();
            ContentValues record=new ContentValues();
            record.put("assignment_id",id); record.put("payload_type",payloadType);
            record.put("payload_json",payload); record.put("created_at",now);
            db.insertOrThrow("assignment_payloads",null,record);

            // Convenience snapshot only; the immutable payload table is the history of record.
            ContentValues latest=new ContentValues(); latest.put(column,payload);
            latest.put("updated_at",now);
            db.update("assignments",latest,"assignment_id=?",new String[]{id});
            event(db,id,null,current,payloadType,detail);
            db.setTransactionSuccessful();
        } finally { db.endTransaction(); }
    }

    public synchronized List<Payload> listPayloads(String assignmentId) {
        List<Payload> out=new ArrayList<>();
        try (Cursor c=getReadableDatabase().query("assignment_payloads",null,
                "assignment_id=?",new String[]{assignmentId},null,null,"payload_id ASC")) {
            while(c.moveToNext()) out.add(new Payload(c));
        }
        return Collections.unmodifiableList(out);
    }

    private static String statusOf(SQLiteDatabase db,String id) {
        try(Cursor c=db.rawQuery("SELECT status FROM assignments WHERE assignment_id=?",
                new String[]{id})) {
            if(!c.moveToFirst()) throw new IllegalArgumentException("Unknown assignment: "+id);
            return c.getString(0);
        }
    }

    private static void event(SQLiteDatabase db,String id,Status from,Status to,
            String eventType,String detail) {
        ContentValues e=new ContentValues(); e.put("assignment_id",id);
        if(from==null)e.putNull("from_status"); else e.put("from_status",from.name());
        e.put("to_status",to.name()); e.put("event_type",eventType);
        e.put("detail",detail); e.put("created_at",System.currentTimeMillis());
        db.insertOrThrow("assignment_events",null,e);
    }

    private static boolean allowed(Status from,Status to) {
        if(from==to) return false;
        switch(from) {
            case CREATED: return to==Status.AUTHORIZED || to==Status.CLOSED;
            case AUTHORIZED: return to==Status.QUEUED || to==Status.BLOCKED || to==Status.CLOSED;
            case QUEUED: return to==Status.ACCEPTED || to==Status.BLOCKED || to==Status.INTERRUPTED || to==Status.CLOSED;
            case ACCEPTED: return to==Status.RUNNING || to==Status.BLOCKED || to==Status.INTERRUPTED;
            case RUNNING: return to==Status.CHECKPOINTED || to==Status.BLOCKED || to==Status.INTERRUPTED || to==Status.RETURNED;
            case CHECKPOINTED: return to==Status.QUEUED || to==Status.RUNNING || to==Status.BLOCKED || to==Status.INTERRUPTED || to==Status.RETURNED;
            case BLOCKED: return to==Status.QUEUED || to==Status.CLOSED;
            case INTERRUPTED: return to==Status.QUEUED || to==Status.CLOSED;
            case RETURNED: return to==Status.VERIFIED || to==Status.CLOSED;
            case VERIFIED: return to==Status.CLOSED;
            case CLOSED: return false;
            default: return false;
        }
    }

    private static void validateCharacter(String character) {
        requireText(character,"character");
        try { BuilderRole.valueOf(character); }
        catch(IllegalArgumentException invalid) {
            throw new IllegalArgumentException("Unknown Nine Character: "+character);
        }
    }

    private static void requireText(String value,String field) {
        if(value==null || value.trim().isEmpty()) throw new IllegalArgumentException(field+" must not be blank");
    }
}
