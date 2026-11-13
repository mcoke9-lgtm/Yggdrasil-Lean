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
import java.util.HashSet;
import java.util.List;
import java.util.Set;
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

        Assessment(CharacterAssignmentReconciliation.Finding finding,
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

        Report(List<Assessment> assessments) {
            this.auditId = UUID.randomUUID().toString();
            this.createdAtEpochMillis = System.currentTimeMillis();
            Set<String> assignmentIds = new HashSet<>();
            for (Assessment item : assessments) assignmentIds.add(item.finding.assignmentId);
            this.assignmentsScanned = assignmentIds.size();
            this.assessments = immutable(assessments);
        }

        /** Number of distinct assignments with at least one review-required finding. */
        public int discrepancyCount() {
            Set<String> assignmentsWithDiscrepancies = new HashSet<>();
            for (Assessment item : assessments) {
                if (item.finding.requiresReview()) {
                    assignmentsWithDiscrepancies.add(item.finding.assignmentId);
                }
            }
            return assignmentsWithDiscrepancies.size();
        }

        /** Total number of findings, including multiple findings for one assignment. */
        public int findingCount() {
            return assessments.size();
        }

        /** Number of individual findings that require review. */
        public int discrepancyFindingCount() {
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
            List<String> journalReportFingerprints = new ArrayList<>();
            boolean malformedJournalReport = false;
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
                    if (fingerprint == null) malformedJournalReport = true;
                    else journalReportFingerprints.add(fingerprint);
                }
            }

            List<String> ledgerIds = new ArrayList<>();
            boolean ledgerAccepted = ledger.hasLifecycleEvent(
                    assignment.assignmentId, CharacterAssignmentStore.Status.ACCEPTED.name());
            if (ledgerAccepted) ledgerIds.add("lifecycle:ACCEPTED");

            List<String> ledgerReportFingerprints = new ArrayList<>();
            boolean malformedLedgerReport = false;
            List<CharacterAssignmentStore.Payload> payloads = ledger.listPayloads(assignment.assignmentId);
            for (CharacterAssignmentStore.Payload payload : payloads) {
                if (CharacterAssignmentStore.PAYLOAD_RETURN_REPORT.equals(payload.payloadType)) {
                    ledgerIds.add("payload:" + payload.payloadId);
                    try {
                        String fingerprint = fingerprintJson(payload.payloadJson);
                        if (fingerprint == null) throw new IllegalArgumentException("empty report payload");
                        ledgerReportFingerprints.add(fingerprint);
                    } catch (IllegalArgumentException malformed) {
                        malformedLedgerReport = true;
                        warnings.add("Ledger return-report payload " + payload.payloadId + " is malformed");
                    }
                }
            }

            CharacterAssignmentReconciliation.Finding acceptance =
                    CharacterAssignmentReconciliation.acceptance(
                            assignment.assignmentId, ledgerAccepted, request, acknowledgement);
            assessments.add(new Assessment(acceptance, ledgerIds, journalIds, warnings));

            String ledgerReportFingerprint = auditFingerprint(
                    ledgerReportFingerprints, malformedLedgerReport, "ledger");
            String journalReportFingerprint = auditFingerprint(
                    journalReportFingerprints, malformedJournalReport, "journal");
            if (distinctCount(journalReportFingerprints) > 1) {
                warnings.add("Multiple distinct journal return reports are preserved for review");
            } else if (journalReportCount > 1 && !malformedJournalReport) {
                warnings.add("Multiple identical journal return reports are present; inspect their source turns");
            }
            if (distinctCount(ledgerReportFingerprints) > 1) {
                warnings.add("Multiple distinct ledger return reports are preserved for review");
            } else if (ledgerReportFingerprints.size() > 1 && !malformedLedgerReport) {
                warnings.add("Multiple identical ledger return reports are present; inspect their source payloads");
            }
            CharacterAssignmentReconciliation.Finding report =
                    CharacterAssignmentReconciliation.returnReport(
                            assignment.assignmentId, ledgerReportFingerprint, journalReportFingerprint);
            if (journalReportCount > 0 || !ledgerIds.isEmpty() && hasReportPayload(ledgerIds)) {
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

    /** Collapse a store's report history without discarding conflicts or unreadable entries. */
    static String auditFingerprint(List<String> fingerprints, boolean malformed, String store) {
        if (malformed) return "unreadable-" + store + "-report";
        int distinct = distinctCount(fingerprints);
        if (distinct > 1) return "multiple-distinct-" + store + "-reports";
        return fingerprints.isEmpty() ? null : fingerprints.get(0);
    }

    private static int distinctCount(List<String> fingerprints) {
        List<String> distinct = new ArrayList<>();
        for (String fingerprint : fingerprints) {
            if (!distinct.contains(fingerprint)) distinct.add(fingerprint);
        }
        return distinct.size();
    }

    private static boolean hasReportPayload(List<String> evidenceIds) {
        for (String id : evidenceIds) if (id.startsWith("payload:")) return true;
        return false;
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
        if (json == null || json.trim().isEmpty()) {
            throw new IllegalArgumentException("Report payload must not be empty");
        }
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
