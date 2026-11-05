package org.yggdrasil.keysteward;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.FileOutputStream;
import java.security.KeyStore;

import org.junit.BeforeClass;
import org.junit.Test;

public class KeyStoreEngineTest {
    private static final char[] PASSWORD = "disposable-test-password".toCharArray();
    private static byte[] encodedStore;
    private static KeyStoreEngine.Verification created;

    @BeforeClass
    public static void createOneDisposableFixture() throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        created = KeyStoreEngine.create(output, PASSWORD);
        encodedStore = output.toByteArray();
        assertTrue(encodedStore.length > 0);
    }

    @Test
    public void exportedPkcs12CanBeReopenedAndFingerprintMatches() throws Exception {
        KeyStoreEngine.Verification reopened = KeyStoreEngine.verify(
                new ByteArrayInputStream(encodedStore), PASSWORD);
        assertEquals(created.fingerprint, reopened.fingerprint);
        assertEquals("RSA", reopened.algorithm);
        assertEquals("builder-upload-key", KeyStoreEngine.ALIAS);

        // Gradle's signing task uses the JDK provider, not the app's BC provider.
        KeyStore jdkStore = KeyStore.getInstance("PKCS12");
        jdkStore.load(new ByteArrayInputStream(encodedStore), PASSWORD);
        assertTrue(jdkStore.isKeyEntry(KeyStoreEngine.ALIAS));
    }

    @Test
    public void writesAppGeneratedDisposablePkcs12ForGradleSigningWhenRequested()
            throws Exception {
        String outputPath = System.getProperty("builder.test.keystore.output");
        if (outputPath == null || outputPath.isEmpty()) return;
        try (FileOutputStream output = new FileOutputStream(outputPath)) {
            output.write(encodedStore);
        }
    }

    @Test
    public void wrongPasswordIsRejected() {
        assertThrows(Exception.class, () -> KeyStoreEngine.verify(
                new ByteArrayInputStream(encodedStore),
                "incorrect-test-password".toCharArray()));
    }

    @Test
    public void alteredKeystoreIsRejected() {
        byte[] altered = encodedStore.clone();
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
