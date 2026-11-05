package org.yggdrasil.scheduler.builder;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.util.Arrays;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/**
 * Device-local provider settings. Non-secret metadata is stored in private
 * SharedPreferences; API tokens are AES-GCM encrypted with a non-exportable
 * Android Keystore key. This class never logs or returns tokens in status text.
 */
public final class SecureBuilderProviderSettings {
    private static final String PREFS = "builder_provider_settings";
    private static final String KEY_ALIAS_PREFIX = "yggdrasil_builder_provider_token_";
    private final String keyAlias;
    private static final String K_PROVIDER = "provider_id";
    private static final String K_ENDPOINT = "endpoint";
    private static final String K_MODEL = "model";
    private static final String K_TOKEN = "token_ciphertext";
    private static final String K_IV = "token_iv";
    private final SharedPreferences preferences;

    public static final class Configuration {
        public final String providerId;
        public final String endpoint;
        public final String model;
        public final boolean hasCredential;
        Configuration(String providerId, String endpoint, String model, boolean hasCredential) {
            this.providerId = providerId;
            this.endpoint = endpoint;
            this.model = model;
            this.hasCredential = hasCredential;
        }
    }

    /** True only when a saved bearer token is staying with its original provider endpoint. */
    public static boolean canReuseCredential(Configuration current, String providerId, String endpoint) {
        if (current == null || providerId == null || endpoint == null) return false;
        return current.providerId.equals(providerId.trim())
                && current.endpoint.equals(endpoint.trim());
    }

    public SecureBuilderProviderSettings(Context context) {
        this(context, "default");
    }

    /**
     * Isolated credential namespace for one Builder role. Namespace is part of
     * the local Keystore alias and private preference file; tokens are never
     * copied between roles.
     */
    public SecureBuilderProviderSettings(Context context, String namespace) {
        if (context == null) throw new IllegalArgumentException("Context is required");
        String normalized = requireText(namespace, "Settings namespace")
                .toLowerCase(java.util.Locale.ROOT);
        if (!normalized.matches("[a-z0-9_-]{1,48}"))
            throw new IllegalArgumentException("Settings namespace contains unsupported characters");
        preferences = context.getApplicationContext().getSharedPreferences(
                "default".equals(normalized) ? PREFS : PREFS + "_" + normalized,
                Context.MODE_PRIVATE);
        keyAlias = "default".equals(normalized)
                ? "yggdrasil_builder_provider_token_v1" : KEY_ALIAS_PREFIX + normalized;
    }

    public synchronized void save(String providerId, String endpoint, String model, String token)
            throws Exception {
        String id = requireText(providerId, "Provider ID");
        String normalizedEndpoint = validateEndpoint(endpoint);
        String normalizedModel = requireText(model, "Model ID");
        String normalizedToken = token == null ? "" : token.trim();
        boolean local = isLoopback(normalizedEndpoint);
        if (!local && normalizedToken.isEmpty())
            throw new IllegalArgumentException("A token is required for a hosted HTTPS endpoint");

        SharedPreferences.Editor editor = preferences.edit()
                .putString(K_PROVIDER, id)
                .putString(K_ENDPOINT, normalizedEndpoint)
                .putString(K_MODEL, normalizedModel);
        if (normalizedToken.isEmpty()) {
            editor.remove(K_TOKEN).remove(K_IV);
        } else {
            byte[] iv = new byte[12];
            new java.security.SecureRandom().nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey(), new GCMParameterSpec(128, iv));
            byte[] encrypted = cipher.doFinal(normalizedToken.getBytes(StandardCharsets.UTF_8));
            editor.putString(K_TOKEN, Base64.encodeToString(encrypted, Base64.NO_WRAP));
            editor.putString(K_IV, Base64.encodeToString(iv, Base64.NO_WRAP));
            Arrays.fill(encrypted, (byte) 0);
        }
        if (!editor.commit()) throw new IllegalStateException("Could not save provider settings");
    }

    public synchronized Configuration readConfiguration() {
        String id = preferences.getString(K_PROVIDER, null);
        String endpoint = preferences.getString(K_ENDPOINT, null);
        String model = preferences.getString(K_MODEL, null);
        if (id == null || endpoint == null || model == null) return null;
        return new Configuration(id, endpoint, model, preferences.contains(K_TOKEN));
    }

    /** Creates an adapter from the saved configuration; does not make a network request. */
    public synchronized OpenAiCompatibleModelProvider buildProvider() {
        Configuration config = readConfiguration();
        if (config == null) throw new IllegalStateException("No model provider is configured");
        return new OpenAiCompatibleModelProvider(config.providerId, config.endpoint, config.model,
                this::readToken, new UrlConnectionModelTransport(), 15000, 60000);
    }

    /** Returns the decrypted token only to the in-process provider credential adapter. */
    public synchronized String readToken() throws Exception {
        String encoded = preferences.getString(K_TOKEN, null);
        String encodedIv = preferences.getString(K_IV, null);
        if (encoded == null && encodedIv == null) return null;
        if (encoded == null || encodedIv == null)
            throw new IllegalStateException("Stored provider credential is incomplete");
        byte[] encrypted = Base64.decode(encoded, Base64.NO_WRAP);
        byte[] iv = Base64.decode(encodedIv, Base64.NO_WRAP);
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), new GCMParameterSpec(128, iv));
            byte[] plain = cipher.doFinal(encrypted);
            try {
                return new String(plain, StandardCharsets.UTF_8);
            } finally {
                Arrays.fill(plain, (byte) 0);
            }
        } finally {
            Arrays.fill(encrypted, (byte) 0);
            Arrays.fill(iv, (byte) 0);
        }
    }

    public synchronized void clear() throws Exception {
        if (!preferences.edit().clear().commit())
            throw new IllegalStateException("Could not clear provider settings");
        KeyStore store = KeyStore.getInstance("AndroidKeyStore");
        store.load(null);
        if (store.containsAlias(keyAlias)) store.deleteEntry(keyAlias);
    }

    private SecretKey getOrCreateKey() throws Exception {
        KeyStore store = KeyStore.getInstance("AndroidKeyStore");
        store.load(null);
        if (store.containsAlias(keyAlias))
            return ((KeyStore.SecretKeyEntry) store.getEntry(keyAlias, null)).getSecretKey();
        KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        generator.init(new KeyGenParameterSpec.Builder(keyAlias,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .setKeySize(256)
                .build());
        return generator.generateKey();
    }

    private static String validateEndpoint(String value) {
        String candidate = requireText(value, "Endpoint");
        URI uri = URI.create(candidate);
        String scheme = uri.getScheme();
        String host = uri.getHost();
        if (host == null || uri.getUserInfo() != null || uri.getFragment() != null
                || uri.getQuery() != null || uri.getPath() == null || uri.getPath().isEmpty())
            throw new IllegalArgumentException("Enter an absolute endpoint URL without credentials, query, or fragment");
        if (!"https".equalsIgnoreCase(scheme) && !isLoopbackHost(host, scheme))
            throw new IllegalArgumentException("Use HTTPS, or HTTP only for localhost");
        return uri.toString();
    }

    private static boolean isLoopback(String endpoint) {
        URI uri = URI.create(endpoint);
        return isLoopbackHost(uri.getHost(), uri.getScheme());
    }

    private static boolean isLoopbackHost(String host, String scheme) {
        return "http".equalsIgnoreCase(scheme) && "localhost".equalsIgnoreCase(host);
    }

    private static String requireText(String value, String label) {
        if (value == null || value.trim().isEmpty())
            throw new IllegalArgumentException(label + " is required");
        return value.trim();
    }
}
