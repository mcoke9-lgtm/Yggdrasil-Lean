package org.yggdrasil.scheduler.builder;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** Immutable registry of explicitly installed provider adapters. */
public final class BuilderProviderRegistry {
    private final Map<String, BuilderModelProvider> providers;

    public BuilderProviderRegistry(Iterable<BuilderModelProvider> configuredProviders) {
        if (configuredProviders == null)
            throw new IllegalArgumentException("Provider collection is required");
        Map<String, BuilderModelProvider> copy = new LinkedHashMap<>();
        for (BuilderModelProvider provider : configuredProviders) {
            if (provider == null || provider.providerId() == null
                    || provider.providerId().trim().isEmpty())
                throw new IllegalArgumentException("Provider and provider ID are required");
            String id = provider.providerId().trim();
            if (copy.put(id, provider) != null)
                throw new IllegalArgumentException("Duplicate provider ID: " + id);
        }
        providers = Collections.unmodifiableMap(copy);
    }

    public BuilderModelProvider require(String providerId) {
        BuilderModelProvider provider = providers.get(providerId);
        if (provider == null)
            throw new IllegalArgumentException("No configured model provider: " + providerId);
        return provider;
    }

    public Map<String, BuilderModelProvider> all() {
        return providers;
    }
}
