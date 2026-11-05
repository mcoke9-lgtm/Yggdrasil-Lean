package org.yggdrasil.scheduler.builder;

import android.content.Context;
import android.system.Os;
import android.system.OsConstants;
import org.json.JSONObject;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * Coordinates the active conversation/subject database pair through one atomic pointer.
 *
 * Database files are immutable after staging. Activation does not rename, replace,
 * delete, or edit either database. A single fsynced manifest rename selects both
 * files together. Previous generation identity is retained in that same manifest
 * for rollback. This class is intentionally not wired into normal application startup.
 */
public final class BuilderRecoveryActivation {
    private static final String POINTER = "builder-recovery-active.json";
    private static final String POINTER_TEMP = "builder-recovery-active.json.tmp";
    private static final String FORMAT = "yggdrasil-recovery-activation";
    private static final int VERSION = 1;

    private BuilderRecoveryActivation() { }

    public static final class ActivePair {
        public final String generationId;
        public final String previousGenerationId;
        public final String conversationDatabaseName;
        public final String subjectDatabaseName;
        public final int threadCount;
        public final int turnCount;
        public final int subjectEventCount;
        public final String conversationDataSha256;
        public final String subjectDataSha256;

        private ActivePair(JSONObject descriptor, String previous) throws org.json.JSONException {
            generationId = descriptor.getString("generation_id");
            previousGenerationId = previous;
            conversationDatabaseName = descriptor.getString("conversation_database");
            subjectDatabaseName = descriptor.getString("subject_database");
            threadCount = descriptor.getInt("thread_count");
            turnCount = descriptor.getInt("turn_count");
            subjectEventCount = descriptor.getInt("subject_event_count");
            conversationDataSha256 = descriptor.getString("conversation_data_sha256");
            subjectDataSha256 = descriptor.getString("subject_data_sha256");
        }
    }

    /** Verifies the staged pair, persists an immutable descriptor, then atomically switches one pointer. */
    public static synchronized ActivePair activate(Context context,
                                                     BuilderRecoveryStager.StageResult staged) {
        if (context == null || staged == null) throw new IllegalArgumentException("context and staged pair are required");
        if (!staged.readBackVerified) throw new IllegalArgumentException("Staged pair has not passed read-back verification");
        validateId(staged.stagingId);
        String expectedConversation = "builder-recovery-" + staged.stagingId + "-conversations.db";
        String expectedSubject = "builder-recovery-" + staged.stagingId + "-subjects.db";
        if (!expectedConversation.equals(staged.conversationDatabaseName)
                || !expectedSubject.equals(staged.subjectDatabaseName))
            throw new IllegalArgumentException("Staging database names do not match staging identity");

        Context app = context.getApplicationContext();
        verifyPair(app, staged.conversationDatabaseName, staged.subjectDatabaseName,
                staged.threadCount, staged.turnCount, staged.subjectEventCount,
                staged.conversationDataSha256, staged.subjectDataSha256);

        File descriptorFile = descriptorFile(app, staged.stagingId);
        if (descriptorFile.exists()) throw new IllegalStateException("Generation descriptor already exists");
        String previous = readPointer(app).optString("active_generation_id", "");
        if (previous.isEmpty()) previous = null;

        try {
            JSONObject descriptor = new JSONObject();
            descriptor.put("format", "yggdrasil-recovery-generation");
            descriptor.put("format_version", VERSION);
            descriptor.put("generation_id", staged.stagingId);
            descriptor.put("conversation_database", staged.conversationDatabaseName);
            descriptor.put("subject_database", staged.subjectDatabaseName);
            descriptor.put("thread_count", staged.threadCount);
            descriptor.put("turn_count", staged.turnCount);
            descriptor.put("subject_event_count", staged.subjectEventCount);
            descriptor.put("conversation_data_sha256", staged.conversationDataSha256);
            descriptor.put("subject_data_sha256", staged.subjectDataSha256);
            descriptor.put("descriptor_sha256", checksum(descriptor));
            writeNewAtomic(descriptorFile, descriptor.toString());

            JSONObject pointer = new JSONObject();
            pointer.put("format", FORMAT);
            pointer.put("format_version", VERSION);
            pointer.put("active_generation_id", staged.stagingId);
            pointer.put("previous_generation_id", previous == null ? JSONObject.NULL : previous);
            pointer.put("updated_at_epoch_millis", System.currentTimeMillis());
            pointer.put("manifest_sha256", checksum(pointer));
            replaceAtomic(pointerFile(app), pointer.toString(), pointerTempFile(app));
            return new ActivePair(descriptor, previous);
        } catch (Exception e) {
            if (e instanceof IllegalStateException) throw (IllegalStateException) e;
            throw new IllegalStateException("Could not activate staged recovery pair", e);
        }
    }

    /** Returns the active pair only after validating pointer, descriptor, files, and database contents. */
    public static synchronized ActivePair getActive(Context context) {
        if (context == null) throw new IllegalArgumentException("context is required");
        Context app = context.getApplicationContext();
        JSONObject pointer = readPointer(app);
        String active = pointer.optString("active_generation_id", "");
        if (active.isEmpty()) return null;
        String previous = pointer.isNull("previous_generation_id")
                ? null : pointer.optString("previous_generation_id", null);
        JSONObject descriptor = readDescriptor(app, active);
        ActivePair pair;
        try {
            pair = new ActivePair(descriptor, previous);
            verifyPair(app, pair.conversationDatabaseName, pair.subjectDatabaseName,
                    pair.threadCount, pair.turnCount, pair.subjectEventCount,
                    pair.conversationDataSha256, pair.subjectDataSha256);
            return pair;
        } catch (org.json.JSONException e) {
            throw new IllegalStateException("Invalid active recovery descriptor", e);
        }
    }

    /** Atomically swaps active and previous generation IDs; no database file is modified. */
    public static synchronized ActivePair rollback(Context context) {
        if (context == null) throw new IllegalArgumentException("context is required");
        Context app = context.getApplicationContext();
        JSONObject pointer = readPointer(app);
        String active = pointer.optString("active_generation_id", "");
        String previous = pointer.isNull("previous_generation_id")
                ? "" : pointer.optString("previous_generation_id", "");
        if (active.isEmpty() || previous.isEmpty())
            throw new IllegalStateException("No previous recovery generation is available");
        JSONObject target = readDescriptor(app, previous);
        try {
            ActivePair validated = new ActivePair(target, active);
            verifyPair(app, validated.conversationDatabaseName, validated.subjectDatabaseName,
                    validated.threadCount, validated.turnCount, validated.subjectEventCount,
                    validated.conversationDataSha256, validated.subjectDataSha256);
            JSONObject next = new JSONObject();
            next.put("format", FORMAT);
            next.put("format_version", VERSION);
            next.put("active_generation_id", previous);
            next.put("previous_generation_id", active);
            next.put("updated_at_epoch_millis", System.currentTimeMillis());
            next.put("manifest_sha256", checksum(next));
            replaceAtomic(pointerFile(app), next.toString(), pointerTempFile(app));
            return new ActivePair(target, active);
        } catch (org.json.JSONException e) {
            throw new IllegalStateException("Could not roll back recovery activation", e);
        }
    }

    private static void verifyPair(Context app, String conversationName, String subjectName,
                                   int expectedThreads, int expectedTurns, int expectedEvents,
                                   String expectedConversationDigest, String expectedSubjectDigest) {
        requireDatabaseName(conversationName);
        requireDatabaseName(subjectName);
        if (!app.getDatabasePath(conversationName).isFile()
                || !app.getDatabasePath(subjectName).isFile())
            throw new IllegalStateException("A staged database file is missing");
        BuilderConversationStore conversations = new BuilderConversationStore(app, conversationName);
        BuilderSubjectThreadIndex subjects = new BuilderSubjectThreadIndex(app, subjectName);
        try {
            JSONObject conversation = conversations.exportSnapshot();
            JSONObject subject = subjects.exportSnapshot();
            if (conversation.getInt("thread_count") != expectedThreads
                    || conversation.getInt("turn_count") != expectedTurns
                    || subject.getInt("event_count") != expectedEvents
                    || !expectedConversationDigest.equals(sha256(conversation.getJSONArray("threads").toString()))
                    || !expectedSubjectDigest.equals(sha256(subject.getJSONArray("events").toString())))
                throw new IllegalStateException("Staged database contents no longer match the verified stage");
        } catch (org.json.JSONException e) {
            throw new IllegalStateException("Could not verify staged database contents", e);
        } finally {
            conversations.close();
            subjects.close();
        }
    }

    private static JSONObject readPointer(Context app) {
        File file = pointerFile(app);
        if (!file.isFile()) return new JSONObject();
        try {
            JSONObject pointer = new JSONObject(readFile(file));
            if (!FORMAT.equals(pointer.getString("format")) || pointer.getInt("format_version") != VERSION)
                throw new IllegalArgumentException("Unsupported activation manifest");
            verifyChecksum(pointer, "manifest_sha256");
            String active = pointer.getString("active_generation_id");
            validateId(active);
            if (!pointer.isNull("previous_generation_id"))
                validateId(pointer.getString("previous_generation_id"));
            return pointer;
        } catch (Exception e) {
            throw new IllegalStateException("Activation manifest is invalid; refusing to guess active databases", e);
        }
    }

    private static JSONObject readDescriptor(Context app, String id) {
        validateId(id);
        File file = descriptorFile(app, id);
        try {
            JSONObject descriptor = new JSONObject(readFile(file));
            if (!"yggdrasil-recovery-generation".equals(descriptor.getString("format"))
                    || descriptor.getInt("format_version") != VERSION
                    || !id.equals(descriptor.getString("generation_id")))
                throw new IllegalArgumentException("Generation descriptor identity mismatch");
            verifyChecksum(descriptor, "descriptor_sha256");
            return descriptor;
        } catch (Exception e) {
            throw new IllegalStateException("Generation descriptor is missing or invalid: " + id, e);
        }
    }

    private static void verifyChecksum(JSONObject value, String field) throws Exception {
        String claimed = value.getString(field);
        JSONObject copy = new JSONObject(value.toString());
        copy.remove(field);
        if (!claimed.matches("[0-9a-f]{64}") || !claimed.equals(checksum(copy)))
            throw new IllegalArgumentException("Checksum mismatch: " + field);
    }

    private static String sha256(String value) throws Exception {
        byte[] bytes = MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8));
        StringBuilder out = new StringBuilder();
        for (byte b : bytes) out.append(String.format(java.util.Locale.ROOT, "%02x", b & 0xff));
        return out.toString();
    }

    private static String checksum(JSONObject value) throws Exception {
        byte[] bytes = MessageDigest.getInstance("SHA-256")
                .digest(value.toString().getBytes(StandardCharsets.UTF_8));
        StringBuilder out = new StringBuilder();
        for (byte b : bytes) out.append(String.format(java.util.Locale.ROOT, "%02x", b & 0xff));
        return out.toString();
    }

    private static void writeNewAtomic(File target, String value) throws Exception {
        File temp = new File(target.getParentFile(), target.getName() + ".tmp");
        if (target.exists() || temp.exists()) throw new IllegalStateException("Generation file already exists");
        writeSynced(temp, value);
        Os.rename(temp.getAbsolutePath(), target.getAbsolutePath());
        syncDirectory(target.getParentFile());
    }

    private static void replaceAtomic(File target, String value, File temp) throws Exception {
        if (temp.exists() && !temp.delete()) throw new IllegalStateException("Cannot clear stale manifest temp file");
        writeSynced(temp, value);
        // POSIX rename within one directory atomically replaces the old pointer.
        Os.rename(temp.getAbsolutePath(), target.getAbsolutePath());
        syncDirectory(target.getParentFile());
    }

    private static void writeSynced(File file, String value) throws Exception {
        try (FileOutputStream out = new FileOutputStream(file, false)) {
            out.write(value.getBytes(StandardCharsets.UTF_8));
            out.flush();
            out.getFD().sync();
        }
    }

    private static void syncDirectory(File directory) throws Exception {
        java.io.FileDescriptor fd = Os.open(directory.getAbsolutePath(),
                OsConstants.O_RDONLY | OsConstants.O_DIRECTORY, 0);
        try { Os.fsync(fd); } finally { Os.close(fd); }
    }

    private static String readFile(File file) throws Exception {
        byte[] bytes = new byte[(int) file.length()];
        try (java.io.FileInputStream in = new java.io.FileInputStream(file)) {
            int offset = 0;
            while (offset < bytes.length) {
                int count = in.read(bytes, offset, bytes.length - offset);
                if (count < 0) throw new java.io.IOException("Unexpected end of file");
                offset += count;
            }
        }
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private static File pointerFile(Context app) { return new File(app.getFilesDir(), POINTER); }
    private static File pointerTempFile(Context app) { return new File(app.getFilesDir(), POINTER_TEMP); }
    private static File descriptorFile(Context app, String id) {
        return new File(app.getFilesDir(), "builder-recovery-generation-" + id + ".json");
    }
    private static void requireDatabaseName(String value) {
        if (value == null || !value.matches("builder-recovery-[A-Za-z0-9_-]{1,48}-(conversations|subjects)\\.db"))
            throw new IllegalArgumentException("Unexpected recovery database name");
    }
    private static void validateId(String id) {
        if (id == null || !id.matches("[A-Za-z0-9_-]{1,48}"))
            throw new IllegalArgumentException("Invalid recovery generation ID");
    }
}
