package org.yggdrasil.scheduler.builder;

/** Dedicated Android process entry point for MUNINN. */
public final class MuninnProcessService extends BuilderRoleProcessService {
    @Override protected BuilderRole role() { return BuilderRole.MUNINN; }
}
