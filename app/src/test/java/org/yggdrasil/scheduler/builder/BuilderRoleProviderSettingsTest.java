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
}
