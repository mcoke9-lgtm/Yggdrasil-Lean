package org.yggdrasil.keysteward;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.FileOutputStream;
import java.security.KeyStore;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.math.BigInteger;
import java.util.Date;
import java.security.cert.X509Certificate;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.operator.ContentSigner;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import java.security.Provider;
import org.bouncycastle.jce.provider.BouncyCastleProvider;

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
    public void missingExpectedAliasIsRejected() throws Exception {
        Provider provider = new BouncyCastleProvider();
        KeyStore store = KeyStore.getInstance("PKCS12", provider);
        store.load(null, null);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        store.store(output, PASSWORD);
        assertThrows(IllegalArgumentException.class, () -> KeyStoreEngine.verify(
                new ByteArrayInputStream(output.toByteArray()), PASSWORD));
    }

    @Test
    public void exportSizeAcceptsExactLimitAndRejectsOversize() {
        KeyStoreEngine.validateExportSize(KeyStoreEngine.MAX_EXPORT_BYTES);
        assertThrows(IllegalArgumentException.class, () ->
                KeyStoreEngine.validateExportSize(KeyStoreEngine.MAX_EXPORT_BYTES + 1));
    }

    @Test
    public void base64ExportSizeAcceptsExact48KbLimitAndRejectsOversize() {
        KeyStoreEngine.validateBase64ExportSize(KeyStoreEngine.MAX_BASE64_CHARS);
        assertThrows(IllegalArgumentException.class, () ->
                KeyStoreEngine.validateBase64ExportSize(KeyStoreEngine.MAX_BASE64_CHARS + 1));
    }

    @Test
    public void expiredCertificateIsRejected() throws Exception {
        Provider provider = new BouncyCastleProvider();
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA", provider);
        generator.initialize(2048);
        KeyPair pair = generator.generateKeyPair();
        Date notBefore = new Date(System.currentTimeMillis() - 10L * 24 * 60 * 60 * 1000);
        Date notAfter = new Date(System.currentTimeMillis() - 5L * 24 * 60 * 60 * 1000);
        X500Name subject = new X500Name("CN=Expired disposable test");
        JcaX509v3CertificateBuilder builder = new JcaX509v3CertificateBuilder(
                subject, BigInteger.valueOf(12345), notBefore, notAfter,
                subject, pair.getPublic());
        ContentSigner signer = new JcaContentSignerBuilder("SHA256withRSA")
                .setProvider(provider).build(pair.getPrivate());
        X509Certificate certificate = new JcaX509CertificateConverter()
                .setProvider(provider).getCertificate(builder.build(signer));
        KeyStore store = KeyStore.getInstance("PKCS12", provider);
        store.load(null, null);
        store.setKeyEntry(KeyStoreEngine.ALIAS, pair.getPrivate(), PASSWORD,
                new java.security.cert.Certificate[]{certificate});
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        store.store(output, PASSWORD);
        assertThrows(Exception.class, () -> KeyStoreEngine.verify(
                new ByteArrayInputStream(output.toByteArray()), PASSWORD));
    }

    @Test
    public void shortPasswordIsRejectedBeforeKeyGeneration() {
        assertThrows(IllegalArgumentException.class, () -> KeyStoreEngine.create(
                new ByteArrayOutputStream(), "short".toCharArray()));
    }
}
