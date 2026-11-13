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
    private static final int DATABASE_VERSION = 1;

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

    public CharacterAssignmentStore(Context context) {
        super(context.getApplicationContext(), DATABASE_NAME, null, DATABASE_VERSION);
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
                "from_status TEXT, to_status TEXT NOT NULL, detail TEXT NOT NULL, " +
                "created_at INTEGER NOT NULL)");
        db.execSQL("CREATE INDEX assignment_events_assignment ON assignment_events(assignment_id, event_id)");
    }

    @Override public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        throw new IllegalStateException("No destructive Character assignment migration is defined");
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
            event(db,id,null,Status.CREATED,"Assignment recorded");
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
            event(db,id,current,next,detail); db.setTransactionSuccessful();
        } finally { db.endTransaction(); }
    }

    public synchronized void checkpoint(String id, String checkpointJson) {
        requireText(checkpointJson,"checkpointJson");
        updatePayload(id,"checkpoint_json",checkpointJson,"Checkpoint saved");
    }

    public synchronized void returnReport(String id, String reportJson) {
        requireText(reportJson,"reportJson");
        updatePayload(id,"return_report_json",reportJson,"Return report saved");
    }

    private void updatePayload(String id,String column,String payload,String detail) {
        SQLiteDatabase db=getWritableDatabase(); db.beginTransaction();
        try {
            ContentValues v=new ContentValues(); v.put(column,payload);
            v.put("updated_at",System.currentTimeMillis());
            if(db.update("assignments",v,"assignment_id=?",new String[]{id})==0)
                throw new IllegalArgumentException("Unknown assignment: "+id);
            event(db,id,null,Status.valueOf(statusOf(db,id)),detail);
            db.setTransactionSuccessful();
        } finally { db.endTransaction(); }
    }

    private static String statusOf(SQLiteDatabase db,String id) {
        try(Cursor c=db.rawQuery("SELECT status FROM assignments WHERE assignment_id=?",
                new String[]{id})) {
            if(!c.moveToFirst()) throw new IllegalArgumentException("Unknown assignment: "+id);
            return c.getString(0);
        }
    }

    private static void event(SQLiteDatabase db,String id,Status from,Status to,String detail) {
        ContentValues e=new ContentValues(); e.put("assignment_id",id);
        if(from==null)e.putNull("from_status"); else e.put("from_status",from.name());
        e.put("to_status",to.name()); e.put("detail",detail); e.put("created_at",System.currentTimeMillis());
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
