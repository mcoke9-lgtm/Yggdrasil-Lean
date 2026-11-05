package org.yggdrasil.scheduler.builder;

import android.app.Application;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import java.util.Arrays;
import java.util.Collections;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
public class BuilderRoleProviderSettingsTest {
    private BuilderRoleProviderSettings settings() {
        Application app = RuntimeEnvironment.getApplication();
        BuilderRoleProviderSettings settings = new BuilderRoleProviderSettings(app);
        for (BuilderRole role : BuilderRole.values()) {
            try { settings.clear(role); } catch (Exception ignored) { }
        }
        return settings;
    }

    private static void save(BuilderRoleProviderSettings settings, BuilderRole role,
                             String id, String port, String model) throws Exception {
        settings.save(role, id, "http://localhost:" + port + "/v1/chat/completions", model, "");
    }

    @Test public void missingRoleRoutesAreReportedAndNeverInherited() throws Exception {
        BuilderRoleProviderSettings settings = settings();
        save(settings, BuilderRole.HUGINN, "main", "8101", "model-a");
        assertEquals(Collections.singletonList(BuilderRole.KVASIR),
                settings.missingRoutes(Arrays.asList(BuilderRole.HUGINN, BuilderRole.KVASIR)));
        try {
            settings.requireConfigured(Arrays.asList(BuilderRole.HUGINN, BuilderRole.KVASIR));
            fail("Expected missing route rejection");
        } catch (IllegalStateException expected) {
            assertTrue(expected.getMessage().contains("no shared-provider fallback"));
        }
    }

    @Test public void roleRoutesBuildDistinctProviderIdentities() throws Exception {
        BuilderRoleProviderSettings settings = settings();
        save(settings, BuilderRole.HUGINN, "shared-vendor", "8101", "model-a");
        save(settings, BuilderRole.KVASIR, "shared-vendor", "8101", "model-b");
        BuilderProviderRouter router = settings.buildRouter();
        assertNotEquals(router.providerFor(BuilderRole.HUGINN).providerId(),
                router.providerFor(BuilderRole.KVASIR).providerId());
        assertEquals(2, router.routes().size());
    }

    @Test public void selectedRolesCannotReuseTheSameModelId() throws Exception {
        BuilderRoleProviderSettings settings = settings();
        save(settings, BuilderRole.HUGINN, "provider-a", "8101", "shared-model");
        save(settings, BuilderRole.KVASIR, "provider-b", "8102", "SHARED-MODEL");
        try {
            settings.requireConfigured(Arrays.asList(BuilderRole.HUGINN, BuilderRole.KVASIR));
            fail("Expected duplicate model identity rejection");
        } catch (IllegalStateException expected) {
            assertTrue(expected.getMessage().contains("share model ID"));
        }
    }

    @Test public void baldrMustHaveASeparateEndpointModelPair() throws Exception {
        BuilderRoleProviderSettings settings = settings();
        save(settings, BuilderRole.HUGINN, "main", "8101", "model-a");
        save(settings, BuilderRole.BALDR, "review", "8101", "model-a");
        assertFalse(settings.hasDistinctBaldrRoute());
        try {
            settings.requireIndependentBaldrRoute();
            fail("Expected same endpoint/model pair to be rejected");
        } catch (IllegalStateException expected) {
            assertTrue(expected.getMessage().contains("Baldr requires its own"));
        }
    }

    @Test public void baldrMustDifferInBothEndpointAndModel() throws Exception {
        BuilderRoleProviderSettings settings = settings();
        save(settings, BuilderRole.HUGINN, "implementation", "8101", "model-a");
        save(settings, BuilderRole.BALDR, "review", "8102", "model-a");
        assertFalse(settings.hasDistinctBaldrRoute());
        save(settings, BuilderRole.BALDR, "review", "8101", "model-b");
        assertFalse(settings.hasDistinctBaldrRoute());
    }

    @Test public void baldrDistinctRoutePassesMinimumConfigurationCheck() throws Exception {
        BuilderRoleProviderSettings settings = settings();
        save(settings, BuilderRole.HUGINN, "implementation", "8101", "model-a");
        save(settings, BuilderRole.BALDR, "verification", "8102", "model-b");
        assertTrue(settings.hasDistinctBaldrRoute());
        settings.requireIndependentBaldrRoute();
        assertEquals("verification", settings.configuration(BuilderRole.BALDR).providerId);
    }

    @Test public void baldrCannotBeTheOnlyConfiguredRoleAndClaimIndependence() throws Exception {
        BuilderRoleProviderSettings settings = settings();
        save(settings, BuilderRole.BALDR, "verification", "8102", "model-b");
        assertFalse(settings.hasDistinctBaldrRoute());
    }

    @Test public void baldrRejectsEquivalentEndpointWithCosmeticUrlDifferences() throws Exception {
        BuilderRoleProviderSettings settings = settings();
        settings.save(BuilderRole.HUGINN, "implementation",
                "http://LOCALHOST:8101/v1/chat/completions/", "model-a", "");
        settings.save(BuilderRole.BALDR, "verification",
                "http://localhost:8101/v1/chat/completions", "model-b", "");
        assertFalse(settings.hasDistinctBaldrRoute());
    }

    @Test public void baldrMustDifferFromEveryConfiguredRoleNotOnlySelectedRoles() throws Exception {
        BuilderRoleProviderSettings settings = settings();
        save(settings, BuilderRole.HUGINN, "implementation", "8101", "model-a");
        save(settings, BuilderRole.LOKI, "other", "8102", "model-b");
        save(settings, BuilderRole.BALDR, "verification", "8103", "model-c");
        assertTrue(settings.hasDistinctBaldrRoute());
        save(settings, BuilderRole.BALDR, "verification", "8102", "model-c");
        assertFalse(settings.hasDistinctBaldrRoute());
    }
}