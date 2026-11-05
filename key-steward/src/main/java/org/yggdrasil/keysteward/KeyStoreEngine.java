package org.yggdrasil.keysteward;

import java.io.InputStream;
import java.io.OutputStream;
import java.math.BigInteger;
import java.security.Key;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.SecureRandom;
import java.security.Provider;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.util.Date;
import java.util.Locale;

import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.cert.X509CertificateHolder;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.operator.ContentSigner;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;

final class KeyStoreEngine {
    static final String ALIAS = "builder-upload-key";
    private static final Provider PROVIDER = new BouncyCastleProvider();
    private static final int RSA_BITS = 3072;
    private static final long VALIDITY_DAYS = 10000L;

    static final class Verification {
        final String fingerprint;
        final String subject;
        final String algorithm;
        final Date notBefore;
        final Date notAfter;

        Verification(String fingerprint, String subject, String algorithm,
                     Date notBefore, Date notAfter) {
            this.fingerprint = fingerprint;
            this.subject = subject;
            this.algorithm = algorithm;
            this.notBefore = notBefore;
            this.notAfter = notAfter;
        }
    }

    private KeyStoreEngine() { }

    static Verification create(OutputStream destination, char[] password) throws Exception {
        if (destination == null) throw new IllegalArgumentException("No destination selected");
        if (password == null || password.length < 12) {
            throw new IllegalArgumentException("Use a password of at least 12 characters");
        }
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA", PROVIDER);
        generator.initialize(RSA_BITS, new SecureRandom());
        KeyPair pair = generator.generateKeyPair();

        Date notBefore = new Date(System.currentTimeMillis() - 60_000L);
        Date notAfter = new Date(System.currentTimeMillis()
                + VALIDITY_DAYS * 24L * 60L * 60L * 1000L);
        X500Name subject = new X500Name("CN=Builder Key Steward Upload Key");
        BigInteger serial = new BigInteger(159, new SecureRandom()).add(BigInteger.ONE);
        JcaX509v3CertificateBuilder builder = new JcaX509v3CertificateBuilder(
                subject, serial, notBefore, notAfter, subject, pair.getPublic());
        ContentSigner signer = new JcaContentSignerBuilder("SHA256withRSA")
                .setProvider(PROVIDER).build(pair.getPrivate());
        X509CertificateHolder holder = builder.build(signer);
        X509Certificate certificate = new JcaX509CertificateConverter()
                .setProvider(PROVIDER).getCertificate(holder);
        certificate.checkValidity(new Date());
        certificate.verify(pair.getPublic());

        KeyStore store = KeyStore.getInstance("PKCS12", PROVIDER);
        store.load(null, null);
        store.setKeyEntry(ALIAS, pair.getPrivate(), password,
                new Certificate[]{certificate});
        store.store(destination, password);
        destination.flush();
        return describe(certificate);
    }

    static Verification verify(InputStream source, char[] password) throws Exception {
        if (source == null) throw new IllegalArgumentException("No keystore selected");
        ensureProvider();
        KeyStore store = KeyStore.getInstance("PKCS12", PROVIDER);
        store.load(source, password);
        if (!store.containsAlias(ALIAS) || !store.isKeyEntry(ALIAS)) {
            throw new IllegalArgumentException("Expected signing-key alias was not found");
        }
        Key key = store.getKey(ALIAS, password);
        if (!(key instanceof PrivateKey)) {
            throw new IllegalArgumentException("The alias does not contain a private key");
        }
        Certificate certificate = store.getCertificate(ALIAS);
        if (!(certificate instanceof X509Certificate)) {
            throw new IllegalArgumentException("The signing certificate is missing or invalid");
        }
        X509Certificate x509 = (X509Certificate) certificate;
        x509.checkValidity(new Date());
        return describe(x509);
    }

    private static Verification describe(X509Certificate certificate) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256")
                .digest(certificate.getEncoded());
        StringBuilder hex = new StringBuilder();
        for (byte value : digest) {
            hex.append(String.format(Locale.US, "%02X", value & 0xff));
        }
        return new Verification(hex.toString(), certificate.getSubjectX500Principal().getName(),
                certificate.getPublicKey().getAlgorithm(),
                certificate.getNotBefore(), certificate.getNotAfter());
    }
}
