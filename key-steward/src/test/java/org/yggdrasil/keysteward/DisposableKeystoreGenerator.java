package org.yggdrasil.keysteward;

import java.io.FileOutputStream;

/** CI-only disposable key generator. Never use this class for a production identity. */
public final class DisposableKeystoreGenerator {
    private DisposableKeystoreGenerator() { }

    public static void main(String[] args) throws Exception {
        if (args.length != 2) {
            throw new IllegalArgumentException("Expected output path and disposable test password");
        }
        char[] password = args[1].toCharArray();
        try (FileOutputStream output = new FileOutputStream(args[0])) {
            KeyStoreEngine.create(output, password);
        } finally {
            java.util.Arrays.fill(password, '\u0000');
        }
    }
}
