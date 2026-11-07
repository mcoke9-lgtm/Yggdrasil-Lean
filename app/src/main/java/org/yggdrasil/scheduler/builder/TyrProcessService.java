package org.yggdrasil.scheduler.builder;

/** Dedicated Android process entry point for TYR. */
public final class TyrProcessService extends BuilderRoleProcessService {
    @Override protected BuilderRole role() { return BuilderRole.TYR; }
}
