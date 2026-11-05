package org.yggdrasil.scheduler.builder;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

import java.time.LocalDate;
import java.util.Collections;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
public class BuilderOperationalLimitsTest {
    private static final String REGISTER =
            "{\"schema\":\"yggdrasil.operational-capacity-register\","
            + "\"schema_version\":\"1.0.0\","
            + "\"constraints\":[{"
            + "\"constraint_id\":\"tasks\","
            + "\"capability\":\"Create scheduled tasks / automations\","
            + "\"scope\":\"Current account\","
            + "\"state\":\"blocked\","
            + "\"maximum_capacity\":3,\"current_usage\":3,\"remaining_capacity\":0,"
            + "\"unit\":\"slots\",\"evidence_source\":\"Task creation response\","
            + "\"observed_at\":\"2026-11-05\","
            + "\"safe_alternative\":\"Manual one-time review\"}]}";

    private JSONObject constraint(BuilderOperationalLimits.Snapshot snapshot) throws Exception {
        return new JSONObject(snapshot.toPromptJson()).getJSONArray("constraints").getJSONObject(0);
    }

    @Test public void freshBlockedEvidenceRemainsBlockedWithCapacityAndSource() throws Exception {
        BuilderOperationalLimits.Snapshot snapshot = BuilderOperationalLimits.fromJson(
                REGISTER, LocalDate.parse("2026-11-05"));
        JSONObject entry = constraint(snapshot);
        assertEquals("BLOCKED", entry.getString("gate_decision"));
        assertEquals(0, entry.getInt("remaining_capacity"));
        assertEquals("Task creation response", entry.getString("evidence_source"));
        assertEquals(0, entry.getLong("evidence_age_days"));
    }

    @Test public void staleBlockedEvidenceRequiresRecheckInsteadOfAssumingCurrentBlock() throws Exception {
        BuilderOperationalLimits.Snapshot snapshot = BuilderOperationalLimits.fromJson(
                REGISTER, LocalDate.parse("2026-11-20"));
        assertEquals("RECHECK_REQUIRED", constraint(snapshot).getString("gate_decision"));
    }

    @Test public void inconsistentCapacityFailsClosed() throws Exception {
        JSONObject changed = new JSONObject(REGISTER);
        changed.getJSONArray("constraints").getJSONObject(0).put("remaining_capacity", 1);
        BuilderOperationalLimits.Snapshot snapshot = BuilderOperationalLimits.fromJson(
                changed.toString(), LocalDate.parse("2026-11-05"));
        assertEquals("RECHECK_REQUIRED", constraint(snapshot).getString("gate_decision"));
    }

    @Test public void partialCapacityRecordRequiresRecheck() throws Exception {
        JSONObject changed = new JSONObject(REGISTER);
        changed.getJSONArray("constraints").getJSONObject(0).remove("current_usage");
        BuilderOperationalLimits.Snapshot snapshot = BuilderOperationalLimits.fromJson(
                changed.toString(), LocalDate.parse("2026-11-05"));
        assertEquals("RECHECK_REQUIRED", constraint(snapshot).getString("gate_decision"));
    }

    @Test public void bundledAssetLoadsAsSharedSnapshot() throws Exception {
        BuilderOperationalLimits.Snapshot snapshot = BuilderOperationalLimits.load(
                RuntimeEnvironment.getApplication());
        JSONObject root = new JSONObject(snapshot.toPromptJson());
        assertEquals("LOADED", root.getString("register_status"));
        assertEquals("Create scheduled tasks / automations",
                root.getJSONArray("constraints").getJSONObject(0).getString("capability"));
    }

    @Test public void duplicateCapabilityRecordsRequireReconciliation() throws Exception {
        JSONObject changed = new JSONObject(REGISTER);
        JSONObject duplicate = new JSONObject(
                changed.getJSONArray("constraints").getJSONObject(0).toString());
        changed.getJSONArray("constraints").put(duplicate);
        BuilderOperationalLimits.Snapshot snapshot = BuilderOperationalLimits.fromJson(
                changed.toString(), LocalDate.parse("2026-11-05"));
        JSONArray entries = new JSONObject(snapshot.toPromptJson()).getJSONArray("constraints");
        assertEquals(2, entries.length());
        assertEquals("RECHECK_REQUIRED", entries.getJSONObject(0).getString("gate_decision"));
        assertEquals("RECHECK_REQUIRED", entries.getJSONObject(1).getString("gate_decision"));
    }

    @Test public void missingCapabilityIsExplicitlyUnknownNotAvailable() throws Exception {
        BuilderOperationalLimits.Snapshot snapshot = BuilderOperationalLimits.fromJson(
                REGISTER, LocalDate.parse("2026-11-05"));
        JSONObject root = new JSONObject(snapshot.toPromptJson());
        assertEquals("RECHECK_REQUIRED", root.getJSONObject("runtime_evaluation")
                .getString("unlisted_capability_decision"));
        assertFalse(root.getJSONObject("runtime_evaluation").getBoolean("live_telemetry"));
    }

    @Test public void malformedRegisterReturnsUnavailableFailClosedSnapshot() throws Exception {
        JSONObject root = new JSONObject(BuilderOperationalLimits.fromJson(
                "{not-json", LocalDate.parse("2026-11-05")).toPromptJson());
        assertEquals("UNAVAILABLE", root.getString("register_status"));
        assertEquals("RECHECK_REQUIRED", root.getString("unlisted_capability_decision"));
    }

    @Test public void everyBuilderPromptReceivesTheSameLimitsSnapshot() throws Exception {
        BuilderOperationalLimits.Snapshot snapshot = BuilderOperationalLimits.fromJson(
                REGISTER, LocalDate.parse("2026-11-05"));
        BuilderConversationProtocol.Turn user = new BuilderConversationProtocol.Turn(
                BuilderConversationProtocol.CURRENT_VERSION, "limits-thread", "u1",
                BuilderConversationProtocol.Participant.USER, null, null,
                BuilderConversationProtocol.TurnType.CONTRIBUTION,
                BuilderConversationProtocol.EvidenceStatus.UNKNOWN, 100,
                "Review the operational constraints.");
        String expected = snapshot.toPromptJson();
        for (BuilderRole role : BuilderRole.values()) {
            BuilderPromptFactory.Prompt prompt = BuilderPromptFactory.forTurn(
                    role, Collections.singletonList(user), user, expected);
            JSONObject payload = new JSONObject(prompt.userMessage);
            assertEquals("BLOCKED", payload.getJSONObject("operational_limits_snapshot")
                    .getJSONArray("constraints").getJSONObject(0).getString("gate_decision"));
            assertTrue(prompt.systemInstruction.contains("A capability absent from the snapshot is unknown"));
        }
    }
}
