package org.yggdrasil.keysteward;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.math.BigInteger;
import java.security.Key;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.util.Date;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x509.BasicConstraints;
import org.bouncycastle.asn1.x509.Extension;
import org.bouncycastle.asn1.x509.KeyUsage;
import org.bouncycastle.cert.X509CertificateHolder;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.operator.ContentSigner;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.bouncycastle.jce.provider.BouncyCastleProvider;

/** Offline-only PKCS#12 signing keystore generator and verifier. */
public final class KeystoreMaterial {
    public static final String ALIAS = "yggdrasil-builder-host";
    public static final String STORE_TYPE = "PKCS12";
    private static final int RSA_BITS = 3072;
    private static final long VALIDITY_DAYS = 365L * 25L;
    private KeystoreMaterial() {}

    public static final class Generated {
        public final byte[] keystoreBytes;
        public final String certificateSha256;
        public Generated(byte[] bytes, String fingerprint) {
            this.keystoreBytes = bytes;
            this.certificateSha256 = fingerprint;
        }
    }

    public static Generated generate(char[] password) throws Exception {
        requirePassword(password);
        SecureRandom random = new SecureRandom();
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(RSA_BITS, random);
        KeyPair pair = generator.generateKeyPair();

        BouncyCastleProvider bc = new BouncyCastleProvider();
        X500Name subject = new X500Name(
                "CN=Yggdrasil Builder Host, OU=Nine Builders, O=Yggdrasil, C=US");
        Date notBefore = new Date(System.currentTimeMillis() - 86_400_000L);
        Date notAfter = new Date(System.currentTimeMillis()
                + VALIDITY_DAYS * 86_400_000L);
        BigInteger serial = new BigInteger(159, random).abs().add(BigInteger.ONE);
        JcaX509v3CertificateBuilder builder = new JcaX509v3CertificateBuilder(
                subject, serial, notBefore, notAfter, subject, pair.getPublic());
        builder.addExtension(Extension.basicConstraints, true, new BasicConstraints(false));
        builder.addExtension(Extension.keyUsage, true,
                new KeyUsage(KeyUsage.digitalSignature));
        ContentSigner signer = new JcaContentSignerBuilder("SHA256withRSA")
                .setProvider(bc).build(pair.getPrivate());
        X509CertificateHolder holder = builder.build(signer);
        X509Certificate certificate = new JcaX509CertificateConverter()
                .setProvider(bc).getCertificate(holder);
        certificate.verify(pair.getPublic());

        KeyStore store = KeyStore.getInstance(STORE_TYPE);
        store.load(null, password);
        store.setKeyEntry(ALIAS, pair.getPrivate(), password,
                new Certificate[] { certificate });
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        store.store(output, password);
        byte[] bytes = output.toByteArray();

        String fingerprint = inspect(bytes, password);
        return new Generated(bytes, fingerprint);
    }

    /** Validates password, alias, private-key entry and self-signed certificate. */
    public static String inspect(byte[] bytes, char[] password) throws Exception {
        requirePassword(password);
        KeyStore store = KeyStore.getInstance(STORE_TYPE);
        store.load(new ByteArrayInputStream(bytes), password);
        if (!store.containsAlias(ALIAS) || !store.isKeyEntry(ALIAS)) {
            throw new IllegalArgumentException("Expected signing alias is missing.");
        }
        Key key = store.getKey(ALIAS, password);
        if (key == null || key.getEncoded() == null) {
            throw new IllegalArgumentException("Private-key entry could not be read.");
        }
        Certificate raw = store.getCertificate(ALIAS);
        if (!(raw instanceof X509Certificate)) {
            throw new IllegalArgumentException("Signing certificate is missing.");
        }
        X509Certificate certificate = (X509Certificate) raw;
        certificate.checkValidity();
        certificate.verify(certificate.getPublicKey());
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(certificate.getEncoded());
        return hexFingerprint(digest);
    }

    private static void requirePassword(char[] password) {
        if (password == null || password.length < 16) {
            throw new IllegalArgumentException("Use a password with at least 16 characters.");
        }
    }

    private static String hexFingerprint(byte[] bytes) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < bytes.length; i++) {
            if (i > 0) out.append(':');
            out.append(String.format(java.util.Locale.ROOT, "%02X", bytes[i] & 0xff));
        }
        return out.toString();
    }
}
