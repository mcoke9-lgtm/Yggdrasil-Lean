package org.yggdrasil.scheduler.builder;

import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Read-only recovery rehearsal. It assesses a supplied export and never restores or mutates data.
 */
public final class BuilderRecoveryDrill {
    private BuilderRecoveryDrill() {}

    public static final class Report {
        public final boolean integrityValid;
        public final boolean checksumValid;
        public final String provenanceState;
        public final String sourceComparison;
        public final String readiness;
        public final List<String> findings;

        private Report(boolean integrityValid, boolean checksumValid, String provenanceState,
                       String sourceComparison, String readiness, List<String> findings) {
            this.integrityValid = integrityValid;
            this.checksumValid = checksumValid;
            this.provenanceState = provenanceState;
            this.sourceComparison = sourceComparison;
            this.readiness = readiness;
            this.findings = Collections.unmodifiableList(new ArrayList<>(findings));
        }

        public JSONObject toJson() {
            JSONObject result = new JSONObject();
            try {
                result.put("drill", "yggdrasil-recovery-drill-preview");
                result.put("drill_version", 1);
                result.put("mode", "read_only_preview");
                result.put("integrity_valid", integrityValid);
                result.put("checksum_valid", checksumValid);
                result.put("provenance_state", provenanceState);
                result.put("source_comparison", sourceComparison);
                result.put("readiness", readiness);
                result.put("restoration_performed", false);
                result.put("overwrite_performed", false);
                result.put("deletion_performed", false);
                result.put("findings", new JSONArray(findings));
            } catch (org.json.JSONException impossible) {
                throw new IllegalStateException("Could not create recovery drill report", impossible);
            }
            return result;
        }
    }

    /**
     * expectedSourceEvents must be an independently retained source capture.
     * Passing null skips comparison and can never produce a fully-ready result.
     */
    public static Report assess(JSONObject backup, JSONArray expectedSourceEvents) {
        BuilderRecoveryIntegrity.Report integrity = BuilderRecoveryIntegrity.validate(backup);
        List<String> findings = new ArrayList<>(integrity.errors);
        String provenanceState = "unknown";
        String sourceComparison = "not_run";
        String readiness = "not_ready";

        if (backup != null) {
            JSONObject provenance = backup.optJSONObject("operational_snapshot_provenance");
            if (provenance != null) provenanceState = provenance.optString("status", "unknown");
        }

        if (integrity.valid) {
            if (!integrity.provenanceAvailable) {
                sourceComparison = "not_applicable_unavailable";
                readiness = "ready_with_limitations";
                findings.add("source_provenance_unavailable");
            } else if (expectedSourceEvents == null) {
                sourceComparison = "not_run";
                readiness = "ready_with_limitations";
                findings.add("independent_source_capture_not_supplied");
            } else if (BuilderRecoveryIntegrity.reconcileProvenance(expectedSourceEvents, backup)) {
                sourceComparison = "match";
                readiness = "ready";
                findings.add("backup_integrity_and_source_provenance_match");
            } else {
                sourceComparison = "mismatch";
                findings.add("source_provenance_mismatch");
            }
        } else {
            findings.add("backup_integrity_validation_failed");
        }

        return new Report(integrity.valid, integrity.checksumValid, provenanceState,
                sourceComparison, readiness, findings);
    }
}
