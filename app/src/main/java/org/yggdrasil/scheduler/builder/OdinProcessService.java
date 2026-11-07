package org.yggdrasil.scheduler.builder;

/** Dedicated Android process entry point for ODIN. */
public final class OdinProcessService extends BuilderRoleProcessService {
    @Override protected BuilderRole role() { return BuilderRole.ODIN; }
}
