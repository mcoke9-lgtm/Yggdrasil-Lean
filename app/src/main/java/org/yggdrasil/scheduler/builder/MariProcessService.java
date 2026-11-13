package org.yggdrasil.scheduler.builder;

/** Dedicated Android process entry point for MARI. */
public final class MariProcessService extends BuilderRoleProcessService {
    @Override protected BuilderRole role() { return BuilderRole.MARI; }
}
