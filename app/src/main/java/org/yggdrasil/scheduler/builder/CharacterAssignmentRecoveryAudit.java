package org.yggdrasil.scheduler.builder;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/**
 * Read-only assessment of cross-store evidence for currently open assignments.
 *
 * The audit reads the assignment ledger and append-only conversation journal, then
 * returns findings, advisory plans, and source identifiers. It never writes either
 * store, schedules work, advances lifecycle, or treats a plan as authorization.
 */
public final class CharacterAssignmentRecoveryAudit {
    public static final class Assessment {
        public final CharacterAssignmentReconciliation.Finding finding;
        public final CharacterAssignmentRecoveryPlan.Plan plan;
        public final List<String> ledgerEvidenceIds;
        public final List<String> journalEvidenceIds;
        public final List<String> warnings;

        private Assessment(CharacterAssignmentReconciliation.Finding finding,
                List<String> ledgerIds, List<String> journalIds, List<String> warnings) {
            this.finding = finding;
            this.plan = CharacterAssignmentRecoveryPlan.from(finding);
            this.ledgerEvidenceIds = immutable(ledgerIds);
            this.journalEvidenceIds = immutable(journalIds);
            this.warnings = immutable(warnings);
        }
    }

    public static final class Report {
        public final String auditId;
        public final long createdAtEpochMillis;
        public final int assignmentsScanned;
        public final List<Assessment> assessments;

        private Report(List<Assessment> assessments) {
            this.auditId = UUID.randomUUID().toString();
            this.createdAtEpochMillis = System.currentTimeMillis();
            this.assignmentsScanned = assessments.size();
            this.assessments = immutable(assessments);
        }

        public int discrepancyCount() {
            int count = 0;
            for (Assessment item : assessments) if (item.finding.requiresReview()) count++;
            return count;
        }
    }

    private CharacterAssignmentRecoveryAudit() {}

    /**
     * Scan all non-closed assignments. Closed assignments and journal threads without
     * a corresponding open ledger record are deliberately outside this first scope.
     */
    public static Report scan(CharacterAssignmentStore ledger,
            BuilderConversationJournal journal) {
        if (ledger == null) throw new IllegalArgumentException("ledger is required");
        if (journal == null) throw new IllegalArgumentException("journal is required");

        List<Assessment> assessments = new ArrayList<>();
        for (CharacterAssignmentStore.Assignment assignment : ledger.listOpen()) {
            List<BuilderConversationProtocol.Turn> turns =
                    journal.readThread(CharacterAssignmentJournalBridge.threadId(assignment.assignmentId));
            List<String> journalIds = new ArrayList<>();
            List<String> warnings = new ArrayList<>();
            boolean request = false;
            boolean acknowledgement = false;
            String journalReportFingerprint = null;
            int journalReportCount = 0;

            for (BuilderConversationProtocol.Turn turn : turns) {
                if (!assignment.assignmentId.equals(turn.assignmentId)) {
                    warnings.add("Turn " + turn.turnId + " has missing or mismatched assignment provenance");
                    continue;
                }
                String event = eventOf(turn, warnings);
                if ("ASSIGNMENT_REQUEST".equals(event)) {
                    request = true;
                    journalIds.add(turn.turnId);
                } else if ("ACCEPTED".equals(event)) {
                    acknowledgement = true;
                    journalIds.add(turn.turnId);
                } else if ("RETURN_REPORT".equals(event)) {
                    journalReportCount++;
                    journalIds.add(turn.turnId);
                    String fingerprint = reportFingerprintFromTurn(turn, warnings);
                    if (journalReportFingerprint == null) journalReportFingerprint = fingerprint;
                    else if (fingerprint != null && !journalReportFingerprint.equals(fingerprint)) {
                        journalReportFingerprint = "multiple-distinct-journal-reports";
                        warnings.add("Multiple distinct journal return reports are preserved for review");
                    }
                }
            }

            List<String> ledgerIds = new ArrayList<>();
            boolean ledgerAccepted = ledger.hasLifecycleEvent(
                    assignment.assignmentId, CharacterAssignmentStore.Status.ACCEPTED.name());
            if (ledgerAccepted) ledgerIds.add("lifecycle:ACCEPTED");

            String ledgerReportFingerprint = null;
            List<CharacterAssignmentStore.Payload> payloads = ledger.listPayloads(assignment.assignmentId);
            for (CharacterAssignmentStore.Payload payload : payloads) {
                if (CharacterAssignmentStore.PAYLOAD_RETURN_REPORT.equals(payload.payloadType)) {
                    ledgerIds.add("payload:" + payload.payloadId);
                    try {
                        ledgerReportFingerprint = fingerprintJson(payload.payloadJson);
                    } catch (IllegalArgumentException malformed) {
                        ledgerReportFingerprint = "malformed-ledger-report";
                        warnings.add("Ledger return-report payload " + payload.payloadId + " is malformed");
                    }
                }
            }

            CharacterAssignmentReconciliation.Finding acceptance =
                    CharacterAssignmentReconciliation.acceptance(
                            assignment.assignmentId, ledgerAccepted, request, acknowledgement);
            assessments.add(new Assessment(acceptance, ledgerIds, journalIds, warnings));

            CharacterAssignmentReconciliation.Finding report =
                    CharacterAssignmentReconciliation.returnReport(
                            assignment.assignmentId, ledgerReportFingerprint, journalReportFingerprint);
            if (journalReportCount > 0 && journalReportFingerprint == null) {
                journalReportFingerprint = "unreadable-journal-report";
                warnings.add("Journal return-report evidence could not be fingerprinted");
            } else if (journalReportCount > 1 && !"multiple-distinct-journal-reports".equals(journalReportFingerprint)) {
                warnings.add("Multiple journal return reports are present; inspect their source turns");
            }
            if (ledgerReportFingerprint != null || journalReportFingerprint != null || journalReportCount > 0) {
                List<String> reportLedgerIds = new ArrayList<>();
                List<String> reportJournalIds = new ArrayList<>();
                for (String id : ledgerIds) if (id.startsWith("payload:")) reportLedgerIds.add(id);
                for (String id : journalIds) {
                    for (BuilderConversationProtocol.Turn turn : turns) {
                        if (id.equals(turn.turnId) && "RETURN_REPORT".equals(eventOf(turn, new ArrayList<String>()))) {
                            reportJournalIds.add(id);
                        }
                    }
                }
                assessments.add(new Assessment(report, reportLedgerIds, reportJournalIds, warnings));
            }
        }
        return new Report(assessments);
    }

    private static String eventOf(BuilderConversationProtocol.Turn turn, List<String> warnings) {
        try {
            return new JSONObject(turn.body).optString("event", null);
        } catch (JSONException malformed) {
            warnings.add("Turn " + turn.turnId + " has a malformed event envelope");
            return null;
        }
    }

    private static String reportFingerprintFromTurn(BuilderConversationProtocol.Turn turn,
            List<String> warnings) {
        try {
            JSONObject envelope = new JSONObject(turn.body);
            JSONObject report = envelope.optJSONObject("report");
            if (report == null) {
                warnings.add("Return-report turn " + turn.turnId + " has no report object");
                return null;
            }
            return fingerprintJson(report.toString());
        } catch (JSONException malformed) {
            warnings.add("Return-report turn " + turn.turnId + " is malformed");
            return null;
        }
    }

    static String fingerprintJson(String json) {
        if (json == null || json.trim().isEmpty()) return null;
        try {
            Object parsed = json.trim().startsWith("{")
                    ? new JSONObject(json) : new JSONArray(json);
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(canonical(parsed).getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (byte value : digest) hex.append(String.format(java.util.Locale.ROOT, "%02x", value & 0xff));
            return hex.toString();
        } catch (JSONException malformed) {
            throw new IllegalArgumentException("Report payload must be valid JSON", malformed);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    private static String canonical(Object value) throws JSONException {
        if (value == JSONObject.NULL) return "null";
        if (value instanceof JSONObject) {
            JSONObject object = (JSONObject) value;
            List<String> keys = new ArrayList<>();
            java.util.Iterator<String> iterator = object.keys();
            while (iterator.hasNext()) keys.add(iterator.next());
            Collections.sort(keys);
            StringBuilder out = new StringBuilder("{");
            for (int i = 0; i < keys.size(); i++) {
                if (i > 0) out.append(',');
                String key = keys.get(i);
                out.append(JSONObject.quote(key)).append(':').append(canonical(object.get(key)));
            }
            return out.append('}').toString();
        }
        if (value instanceof JSONArray) {
            JSONArray array = (JSONArray) value;
            StringBuilder out = new StringBuilder("[");
            for (int i = 0; i < array.length(); i++) {
                if (i > 0) out.append(',');
                out.append(canonical(array.get(i)));
            }
            return out.append(']').toString();
        }
        if (value instanceof Number) {
            return new BigDecimal(value.toString()).stripTrailingZeros().toPlainString();
        }
        if (value instanceof String) return JSONObject.quote((String) value);
        if (value instanceof Boolean) return value.toString();
        throw new JSONException("Unsupported JSON value");
    }

    private static <T> List<T> immutable(List<T> source) {
        return Collections.unmodifiableList(new ArrayList<>(source));
    }
}
