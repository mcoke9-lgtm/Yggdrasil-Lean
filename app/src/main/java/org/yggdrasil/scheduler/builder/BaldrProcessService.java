package org.yggdrasil.scheduler.builder;

/** Dedicated Android process entry point for BALDR. */
public final class BaldrProcessService extends BuilderRoleProcessService {
    @Override protected BuilderRole role() { return BuilderRole.BALDR; }
}
