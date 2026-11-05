package org.yggdrasil.scheduler.builder;

import android.content.Context;
import java.net.URI;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Device-local, role-scoped model configuration.
 *
 * Each Builder role has a separate encrypted credential namespace. Missing
 * routes fail closed; the legacy shared provider is never silently inherited.
 * A distinct route is configuration evidence only, not proof of organizational
 * or infrastructure independence of the remote model service.
 */
public final class BuilderRoleProviderSettings {
    private final Context context;

    private static final class RoleProvider implements BuilderModelProvider {
        private final String routeId;
        private final BuilderModelProvider delegate;

        RoleProvider(BuilderRole role, BuilderModelProvider delegate) {
            this.routeId = "builder-role-" + role.name().toLowerCase(Locale.ROOT)
                    + "-" + delegate.providerId();
            this.delegate = delegate;
        }

        @Override public String providerId() { return routeId; }
        @Override public Completion complete(Request request) throws Exception {
            return delegate.complete(request);
        }
    }

    public BuilderRoleProviderSettings(Context context) {
        if (context == null) throw new IllegalArgumentException("Context is required");
        this.context = context.getApplicationContext();
    }

    private SecureBuilderProviderSettings settings(BuilderRole role) {
        if (role == null) throw new IllegalArgumentException("Builder role is required");
        return new SecureBuilderProviderSettings(context,
                "role_" + role.name().toLowerCase(Locale.ROOT));
    }

    public SecureBuilderProviderSettings.Configuration configuration(BuilderRole role) {
        return settings(role).readConfiguration();
    }

    public void save(BuilderRole role, String providerId, String endpoint,
                     String model, String token) throws Exception {
        SecureBuilderProviderSettings scoped = settings(role);
        SecureBuilderProviderSettings.Configuration current = scoped.readConfiguration();
        String secret = token == null ? "" : token;
        if (secret.trim().isEmpty() && current != null && current.hasCredential
                && SecureBuilderProviderSettings.canReuseCredential(
                        current, providerId, endpoint)) {
            secret = scoped.readToken();
        }
        scoped.save(providerId, endpoint, model, secret);
    }

    public void clear(BuilderRole role) throws Exception {
        settings(role).clear();
    }

    public List<BuilderRole> missingRoutes(List<BuilderRole> selectedRoles) {
        if (selectedRoles == null || selectedRoles.isEmpty())
            throw new IllegalArgumentException("At least one selected Builder role is required");
        List<BuilderRole> missing = new ArrayList<>();
        for (BuilderRole role : selectedRoles)
            if (configuration(role) == null) missing.add(role);
        return Collections.unmodifiableList(missing);
    }

    public void requireConfigured(List<BuilderRole> selectedRoles) {
        List<BuilderRole> missing = missingRoutes(selectedRoles);
        if (!missing.isEmpty()) {
            List<String> names = new ArrayList<>();
            for (BuilderRole role : missing) names.add(role.getDisplayName());
            throw new IllegalStateException("No role-specific model route configured for: "
                    + android.text.TextUtils.join(", ", names)
                    + ". Configure each selected Builder; no shared-provider fallback is used.");
        }
        Map<String, BuilderRole> modelOwners = new java.util.HashMap<>();
        for (BuilderRole role : selectedRoles) {
            SecureBuilderProviderSettings.Configuration config = configuration(role);
            String modelKey = config.model.trim().toLowerCase(Locale.ROOT);
            BuilderRole prior = modelOwners.put(modelKey, role);
            if (prior != null)
                throw new IllegalStateException("Selected Builders " + prior.getDisplayName()
                        + " and " + role.getDisplayName() + " share model ID '" + config.model
                        + "'. Assign a distinct model ID to each selected role.");
        }
    }

    /**
     * Baldr must use a different canonical endpoint and model ID from every
     * configured non-Baldr role. Canonical comparison catches cosmetic URL
     * differences (host case, default HTTPS port, dot segments, trailing slash).
     * This remains a configuration safeguard, not proof of provider ownership,
     * model lineage, or infrastructure isolation.
     */
    public boolean hasDistinctBaldrRoute() {
        SecureBuilderProviderSettings.Configuration baldr = configuration(BuilderRole.BALDR);
        if (baldr == null) return false;
        boolean hasOtherRoute = false;
        String baldrEndpoint = canonicalEndpointKey(baldr.endpoint);
        String baldrModel = baldr.model.trim().toLowerCase(Locale.ROOT);
        for (BuilderRole role : BuilderRole.values()) {
            if (role == BuilderRole.BALDR) continue;
            SecureBuilderProviderSettings.Configuration other = configuration(role);
            if (other == null) continue;
            hasOtherRoute = true;
            if (baldrEndpoint.equals(canonicalEndpointKey(other.endpoint))
                    || baldrModel.equals(other.model.trim().toLowerCase(Locale.ROOT)))
                return false;
        }
        return hasOtherRoute;
    }

    private static String canonicalEndpointKey(String endpoint) {
        URI uri = URI.create(endpoint).normalize();
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
        int port = uri.getPort();
        if (("https".equals(scheme) && port == 443) || ("http".equals(scheme) && port == 80))
            port = -1;
        // Normalize percent-encoded dot characters before dot-segment
        // removal. Work from the raw path so reserved escapes such as %2F and
        // literal percent escapes remain valid and are not decoded as separators.
        String rawPath = uri.getRawPath() == null ? "" : uri.getRawPath();
        String dotNormalizedInput = rawPath.replaceAll("(?i)%2e", ".");
        String path = URI.create(dotNormalizedInput).normalize().getRawPath();
        while (path.length() > 1 && path.endsWith("/"))
            path = path.substring(0, path.length() - 1);
        return scheme + "://" + host + (port < 0 ? "" : ":" + port) + path;
    }

    public void requireIndependentBaldrRoute() {
        if (!hasDistinctBaldrRoute())
            throw new IllegalStateException("Baldr requires its own configured route and a different "
                    + "canonical endpoint AND model from every configured non-Baldr role. This does not prove "
                    + "provider ownership or infrastructure independence.");
    }

    public BuilderProviderRouter buildRouter() {
        List<BuilderModelProvider> providers = new ArrayList<>();
        Map<BuilderRole, String> routes = new EnumMap<>(BuilderRole.class);
        for (BuilderRole role : BuilderRole.values()) {
            SecureBuilderProviderSettings scoped = settings(role);
            if (scoped.readConfiguration() == null) continue;
            BuilderModelProvider delegate = scoped.buildProvider();
            RoleProvider provider = new RoleProvider(role, delegate);
            providers.add(provider);
            routes.put(role, provider.providerId());
        }
        return new BuilderProviderRouter(new BuilderProviderRegistry(providers), routes);
    }

    public String routeSummary(BuilderRole role) {
        SecureBuilderProviderSettings.Configuration config = configuration(role);
        if (config == null) return role.getDisplayName() + ": NOT CONFIGURED";
        return role.getDisplayName() + ": " + config.providerId + " / " + config.model
                + (config.hasCredential ? " · credential stored" : " · no credential");
    }
}