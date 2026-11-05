package org.yggdrasil.scheduler.builder;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.*;

public class BuilderSubjectResolverTest {
    private BuilderSubjectResolver resolver() throws Exception {
        String json = "{\"subjects\":[{" +
                "\"subject_id\":\"subject.cryptography\",\"heading\":\"Cryptography\"," +
                "\"recognition_terms\":[\"cryptography\",\"keystore\",\"signing key\"]," +
                "\"stewardship\":[" +
                "{\"builder_id\":\"HUGINN\"},{\"builder_id\":\"MUNINN\"}," +
                "{\"builder_id\":\"KVASIR\"},{\"builder_id\":\"MARI\"}," +
                "{\"builder_id\":\"HODR\"},{\"builder_id\":\"TYR\"}," +
                "{\"builder_id\":\"LOKI\"},{\"builder_id\":\"ODIN\"}," +
                "{\"builder_id\":\"BALDR\"}]," +
                "\"subheadings\":[" +
                "{\"subheading_id\":\"CRYPTO-08\",\"heading\":\"Android application signing\"," +
                "\"recognition_terms\":[\"APK signing\",\"Android app signing\",\"package signing\"]," +
                "\"primary_stewards\":[\"HODR\",\"MUNINN\",\"TYR\"]}," +
                "{\"subheading_id\":\"CRYPTO-05\",\"heading\":\"Digital signatures and certificates\"," +
                "\"recognition_terms\":[\"digital signature\",\"certificate fingerprint\",\"apksigner\"]," +
                "\"primary_stewards\":[\"HODR\",\"TYR\",\"BALDR\"]}," +
                "{\"subheading_id\":\"CRYPTO-04\",\"heading\":\"Hashing and integrity\"," +
                "\"recognition_terms\":[\"SHA-256\",\"checksum\",\"hashing\"]," +
                "\"primary_stewards\":[\"KVASIR\",\"TYR\"]}" +
                "]}]}";
        return new BuilderSubjectResolver(new JSONObject(json));
    }

    @Test public void routesAndroidApplicationSigningToRelevantStewards() throws Exception {
        BuilderSubjectResolver.Resolution result =
                resolver().resolve("The Android app signing identity must remain consistent.");
        assertTrue(result.matched);
        assertFalse(result.ambiguous);
        assertEquals(Arrays.asList("CRYPTO-08"), result.subheadingIds);
        assertEquals(Arrays.asList(BuilderRole.MUNINN, BuilderRole.HODR, BuilderRole.TYR),
                result.selectedRoles);
    }

    @Test public void combinesStewardsAcrossMultipleMatchedSubheadingsWithoutDuplicates() throws Exception {
        BuilderSubjectResolver.Resolution result =
                resolver().resolve("Check the APK signing certificate fingerprint.");
        assertEquals(Arrays.asList("CRYPTO-08", "CRYPTO-05"), result.subheadingIds);
        assertEquals(Arrays.asList(BuilderRole.MUNINN, BuilderRole.HODR,
                BuilderRole.TYR, BuilderRole.BALDR), result.selectedRoles);
    }

    @Test public void broadSubjectHeadingAssemblesAllNineStewards() throws Exception {
        BuilderSubjectResolver.Resolution result = resolver().resolve("Cryptography");
        assertTrue(result.matched);
        assertTrue(result.subheadingIds.isEmpty());
        assertEquals(9, result.selectedRoles.size());
    }

    @Test public void recognizesSubjectThroughSubheadingTerms() throws Exception {
        BuilderSubjectResolver.Resolution result = resolver().resolve("SHA-256 checksum verification");
        assertTrue(result.matched);
        assertEquals(Arrays.asList("CRYPTO-04"), result.subheadingIds);
        assertEquals(Arrays.asList(BuilderRole.KVASIR, BuilderRole.TYR), result.selectedRoles);
    }

    @Test public void unknownSubjectDoesNotInventParticipants() throws Exception {
        BuilderSubjectResolver.Resolution result = resolver().resolve("Maine dairy supply management");
        assertFalse(result.matched);
        assertFalse(result.ambiguous);
        assertTrue(result.selectedRoles.isEmpty());
    }

    @Test public void ambiguousSubjectMatchIsReportedWithoutSelectingRoles() throws Exception {
        JSONObject root = new JSONObject("{\"subjects\":[]}");
        JSONObject first = new JSONObject("{\"subject_id\":\"subject.one\",\"heading\":\"One\",\"recognition_terms\":[\"shared phrase\"],\"stewardship\":[],\"subheadings\":[]}");
        JSONObject second = new JSONObject("{\"subject_id\":\"subject.two\",\"heading\":\"Two\",\"recognition_terms\":[\"shared phrase\"],\"stewardship\":[],\"subheadings\":[]}");
        JSONArray subjects = root.getJSONArray("subjects");
        subjects.put(first);
        subjects.put(second);
        BuilderSubjectResolver.Resolution result =
                new BuilderSubjectResolver(root).resolve("shared phrase");
        assertFalse(result.matched);
        assertTrue(result.ambiguous);
        assertTrue(result.selectedRoles.isEmpty());
    }
}
