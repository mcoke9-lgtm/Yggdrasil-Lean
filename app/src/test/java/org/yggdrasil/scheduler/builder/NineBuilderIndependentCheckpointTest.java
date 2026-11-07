package org.yggdrasil.scheduler.builder;

import android.content.Context;

import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.RobolectricTestRunner;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
public class NineBuilderIndependentCheckpointTest {
    private static final String THREAD_ID = "nine-builder-independent-checkpoints-001";
    private static final String MERGED_DATABASE = "nine-builder-checkpoint-merge-test.db";
    private final Map<BuilderRole, BuilderConversationStore> stores =
            new EnumMap<>(BuilderRole.class);
    private final List<String> databaseNames = new ArrayList<>();

    private BuilderConversationStore openRoleStore(BuilderRole role) {
        String database = "nine-builder-checkpoint-" +
                role.name().toLowerCase(Locale.ROOT) + "-test.db";
        databaseNames.add(database);
        BuilderConversationStore result =
                new BuilderConversationStore(RuntimeEnvironment.getApplication(), database);
        stores.put(role, result);
        return result;
    }

    private BuilderConversationProtocol.Turn checkpoint(
            BuilderRole role, String turnId, long timestamp,
            BuilderConversationProtocol.EvidenceStatus evidence, String body) {
        return new BuilderConversationProtocol.Turn(
                BuilderConversationProtocol.CURRENT_VERSION,
                THREAD_ID,
                turnId,
                BuilderConversationProtocol.Participant.valueOf(role.name()),
                null,
                null,
                BuilderConversationProtocol.TurnType.CONTRIBUTION,
                evidence,
                timestamp,
                body);
    }

    @After public void cleanUp() {
        for (BuilderConversationStore store : stores.values()) store.close();
        Context context = RuntimeEnvironment.getApplication();
        for (String database : databaseNames) context.deleteDatabase(database);
        context.deleteDatabase(MERGED_DATABASE);
    }

    @Test public void nineRoleOwnedCheckpointsSurviveIndependentPauseAndReconcileWithoutErasure()
            throws Exception {
        RuntimeEnvironment.getApplication().deleteDatabase(MERGED_DATABASE);

        // Establish one separately persisted checkpoint database for each named Builder.
        long timestamp = 1000L;
        for (BuilderRole role : BuilderRole.values()) {
            BuilderConversationStore local = openRoleStore(role);
            org.json.JSONObject checkpoint = new org.json.JSONObject();
            checkpoint.put("role", role.name());
            checkpoint.put("display_name", role.getDisplayName());
            checkpoint.put("responsibility", role.getResponsibility());
            checkpoint.put("checkpoint_version", 1);
            checkpoint.put("state", "INITIAL");
            checkpoint.put("authority", "ROLE_LOCAL_ONLY");
            local.append(checkpoint(role, "checkpoint-" + role.name() + "-001",
                    timestamp++, BuilderConversationProtocol.EvidenceStatus.PROPOSED,
                    checkpoint.toString()));
        }
        assertEquals("Every Builder must have a separate local store",
                BuilderRole.values().length, stores.size());
        assertEquals("Each role must have a distinct database",
                BuilderRole.values().length, new HashSet<>(databaseNames).size());

        // Pause all nine stores. Reopen only two roles and let them record distinct,
        // unresolved findings while the rest remain unavailable.
        for (BuilderConversationStore store : stores.values()) store.close();
        stores.clear();

        BuilderConversationStore mari = openRoleStore(BuilderRole.MARI);
        mari.append(checkpoint(BuilderRole.MARI, "mari-offline-update-001", 2000L,
                BuilderConversationProtocol.EvidenceStatus.DISPUTED,
                "{\"issue\":\"shared-interface\",\"finding\":\"Keep both transport paths available.\",\"status\":\"unresolved\"}"));

        BuilderConversationStore loki = openRoleStore(BuilderRole.LOKI);
        loki.append(checkpoint(BuilderRole.LOKI, "loki-offline-update-001", 2001L,
                BuilderConversationProtocol.EvidenceStatus.DISPUTED,
                "{\"issue\":\"shared-interface\",\"finding\":\"Do not assume either transport is currently reachable.\",\"status\":\"unresolved\"}"));

        for (BuilderConversationStore store : stores.values()) store.close();
        stores.clear();

        // Reopen all nine independent stores and collect their append-only histories.
        List<BuilderConversationProtocol.Turn> recovered = new ArrayList<>();
        for (BuilderRole role : BuilderRole.values()) {
            BuilderConversationStore local = openRoleStore(role);
            List<BuilderConversationProtocol.Turn> roleTurns = local.readThread(THREAD_ID);
            assertEquals("Initial checkpoint must survive for " + role.name(),
                    1, roleTurns.stream().filter(t -> t.turnId.equals(
                            "checkpoint-" + role.name() + "-001")).count());
            recovered.addAll(roleTurns);
        }
        Collections.sort(recovered, Comparator.comparingLong(t -> t.timestampEpochMillis));

        // Reconcile by appending every recovered record. No local record is overwritten.
        BuilderConversationStore merged = new BuilderConversationStore(
                RuntimeEnvironment.getApplication(), MERGED_DATABASE);
        List<String> mergedIds = new ArrayList<>();
        for (BuilderConversationProtocol.Turn turn : recovered) {
            merged.append(turn);
            mergedIds.add(turn.turnId);
        }

        List<BuilderConversationProtocol.Turn> finalHistory = merged.readThread(THREAD_ID);
        assertEquals(11, finalHistory.size());
        assertEquals(11, new HashSet<>(mergedIds).size());

        Set<BuilderConversationProtocol.Participant> represented = new HashSet<>();
        int unresolvedFindings = 0;
        for (BuilderConversationProtocol.Turn turn : finalHistory) {
            represented.add(turn.speaker);
            if (turn.evidenceStatus == BuilderConversationProtocol.EvidenceStatus.DISPUTED) {
                unresolvedFindings++;
            }
        }
        assertEquals("All nine Builder identities must remain represented",
                BuilderRole.values().length, represented.size());
        assertTrue(represented.contains(BuilderConversationProtocol.Participant.MARI));
        assertTrue(represented.contains(BuilderConversationProtocol.Participant.LOKI));
        assertEquals("Both divergent findings must remain visible",
                2, unresolvedFindings);
        assertEquals("The two disconnected updates must not overwrite one another",
                "mari-offline-update-001", finalHistory.get(9).turnId);
        assertEquals("The second disconnected update must remain present",
                "loki-offline-update-001", finalHistory.get(10).turnId);
        merged.close();
    }
}
