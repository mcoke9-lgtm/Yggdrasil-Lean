package org.yggdrasil.scheduler.builder;

/** Dedicated Android process entry point for KVASIR. */
public final class KvasirProcessService extends BuilderRoleProcessService {
    @Override protected BuilderRole role() { return BuilderRole.KVASIR; }
}
