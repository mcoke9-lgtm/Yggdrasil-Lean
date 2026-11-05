package org.yggdrasil.scheduler.builder;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Read-only role registry; execution status is tracked separately from identity. */
public final class BuilderRegistry {
    private BuilderRegistry() {}

    public static List<BuilderRole> allRoles() {
        List<BuilderRole> roles = new ArrayList<>();
        Collections.addAll(roles, BuilderRole.values());
        return Collections.unmodifiableList(roles);
    }

    public static BuilderRole find(String name) {
        if (name == null) return null;
        for (BuilderRole role : BuilderRole.values()) {
            if (role.name().equalsIgnoreCase(name)
                    || role.getDisplayName().equalsIgnoreCase(name)) return role;
        }
        return null;
    }
}