package org.yggdrasil.scheduler.builder;

import android.app.Application;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
public class SecureBuilderProviderSettingsTest {
    private SecureBuilderProviderSettings settings() {
        Application app = RuntimeEnvironment.getApplication();
        return new SecureBuilderProviderSettings(app);
    }

    @Test public void rejectsHostedEndpointWithoutCredential() {
        try {
            settings().save("hosted", "https://api.example.test/v1/chat/completions", "model", "");
            fail("Expected missing credential rejection");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("token is required"));
        } catch (Exception unexpected) {
            fail(unexpected.toString());
        }
    }

    @Test public void rejectsNonLoopbackCleartextEndpointBeforeSaving() {
        try {
            settings().save("local", "http://192.168.1.10:8080/v1/chat/completions", "model", "");
            fail("Expected cleartext endpoint rejection");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("HTTPS"));
        } catch (Exception unexpected) {
            fail(unexpected.toString());
        }
    }

    @Test public void rejectsEndpointContainingQueryParameters() {
        try {
            settings().save("hosted", "https://api.example.test/v1/chat?token=secret", "model", "token");
            fail("Expected query parameter rejection");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("without credentials"));
        } catch (Exception unexpected) {
            fail(unexpected.toString());
        }
    }

    @Test public void savesLocalConfigurationWithoutCredentialOrNetworkCall() throws Exception {
        SecureBuilderProviderSettings settings = settings();
        settings.save("local-main", "http://localhost:8080/v1/chat/completions", "local-model", "");
        SecureBuilderProviderSettings.Configuration config = settings.readConfiguration();
        assertNotNull(config);
        assertEquals("local-main", config.providerId);
        assertEquals("local-model", config.model);
        assertFalse(config.hasCredential);
        assertNotNull(settings.buildProvider());
    }

    @Test public void credentialCanBeReusedOnlyForSameProviderAndEndpoint() throws Exception {
        SecureBuilderProviderSettings settings = settings();
        settings.save("hosted-main", "https://api.example.test/v1/chat/completions", "model-a", "test-token");
        SecureBuilderProviderSettings.Configuration config = settings.readConfiguration();
        assertTrue(SecureBuilderProviderSettings.canReuseCredential(
                config, "hosted-main", "https://api.example.test/v1/chat/completions"));
        assertFalse(SecureBuilderProviderSettings.canReuseCredential(
                config, "other-provider", "https://api.example.test/v1/chat/completions"));
        assertFalse(SecureBuilderProviderSettings.canReuseCredential(
                config, "hosted-main", "https://different.example.test/v1/chat/completions"));
    }
}
