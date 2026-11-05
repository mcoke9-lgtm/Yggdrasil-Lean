package org.yggdrasil.scheduler.builder;

import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;

/** Explicit per-role provider selection with no implicit fallback. */
public final class BuilderProviderRouter {
    private final BuilderProviderRegistry registry;
    private final Map<BuilderRole, String> routes;

    public BuilderProviderRouter(BuilderProviderRegistry registry, Map<BuilderRole, String> routes) {
        if (registry == null || routes == null)
            throw new IllegalArgumentException("Provider registry and routes are required");
        EnumMap<BuilderRole, String> copy = new EnumMap<>(BuilderRole.class);
        for (Map.Entry<BuilderRole, String> entry : routes.entrySet()) {
            if (entry.getKey() == null || entry.getValue() == null || entry.getValue().trim().isEmpty())
                throw new IllegalArgumentException("Each route needs a role and provider ID");
            registry.require(entry.getValue().trim());
            copy.put(entry.getKey(), entry.getValue().trim());
        }
        this.registry = registry;
        this.routes = Collections.unmodifiableMap(copy);
    }

    public BuilderModelProvider providerFor(BuilderRole role) {
        if (role == null) throw new IllegalArgumentException("Builder role is required");
        String id = routes.get(role);
        if (id == null) throw new IllegalStateException("No model provider route configured for " + role.name());
        return registry.require(id);
    }

    public Map<BuilderRole, String> routes() { return routes; }
}
