package org.yggdrasil.keysteward;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.security.KeyStore;

import org.junit.Test;

public class KeyStoreEngineTest {
    private static final char[] PASSWORD = "disposable-test-password".toCharArray();

    @Test
    public void exportedPkcs12CanBeReopenedAndFingerprintMatches() throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        KeyStoreEngine.Verification created = KeyStoreEngine.create(output, PASSWORD);
        byte[] encoded = output.toByteArray();
        assertTrue(encoded.length > 0);

        KeyStoreEngine.Verification reopened = KeyStoreEngine.verify(
                new ByteArrayInputStream(encoded), PASSWORD);
        assertEquals(created.fingerprint, reopened.fingerprint);
        assertEquals("RSA", reopened.algorithm);
        assertEquals("builder-upload-key", KeyStoreEngine.ALIAS);

        // Gradle's signing task uses the JDK provider, not the app's BC provider.
        KeyStore jdkStore = KeyStore.getInstance("PKCS12");
        jdkStore.load(new ByteArrayInputStream(encoded), PASSWORD);
        assertTrue(jdkStore.isKeyEntry(KeyStoreEngine.ALIAS));
    }

    @Test
    public void wrongPasswordIsRejected() throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        KeyStoreEngine.create(output, PASSWORD);
        assertThrows(Exception.class, () -> KeyStoreEngine.verify(
                new ByteArrayInputStream(output.toByteArray()),
                "incorrect-test-password".toCharArray()));
    }

    @Test
    public void alteredKeystoreIsRejected() throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        KeyStoreEngine.create(output, PASSWORD);
        byte[] altered = output.toByteArray();
        altered[altered.length / 2] ^= 0x01;
        assertThrows(Exception.class, () -> KeyStoreEngine.verify(
                new ByteArrayInputStream(altered), PASSWORD));
    }

    @Test
    public void shortPasswordIsRejectedBeforeKeyGeneration() {
        assertThrows(IllegalArgumentException.class, () -> KeyStoreEngine.create(
                new ByteArrayOutputStream(), "short".toCharArray()));
    }
}
