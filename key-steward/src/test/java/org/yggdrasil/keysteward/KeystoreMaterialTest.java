package org.yggdrasil.keysteward;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import org.junit.Test;

public class KeystoreMaterialTest {
    @Test public void generatesReloadablePkcs12AndStableFingerprint() throws Exception {
        char[] password = "correct horse battery staple 42".toCharArray();
        KeystoreMaterial.Generated generated = KeystoreMaterial.generate(password);
        assertEquals(95, generated.certificateSha256.length());
        assertEquals(generated.certificateSha256,
                KeystoreMaterial.inspect(generated.keystoreBytes, password));
    }

    @Test public void rejectsWrongPassword() throws Exception {
        char[] password = "correct horse battery staple 42".toCharArray();
        KeystoreMaterial.Generated generated = KeystoreMaterial.generate(password);
        assertThrows(Exception.class, () ->
                KeystoreMaterial.inspect(generated.keystoreBytes,
                        "different password 987654".toCharArray()));
    }

    @Test public void rejectsShortPassword() {
        assertThrows(IllegalArgumentException.class, () ->
                KeystoreMaterial.generate("too-short".toCharArray()));
    }
}
