package org.yggdrasil.scheduler.builder;

/** Dedicated Android process entry point for HODR. */
public final class HodrProcessService extends BuilderRoleProcessService {
    @Override protected BuilderRole role() { return BuilderRole.HODR; }
}
