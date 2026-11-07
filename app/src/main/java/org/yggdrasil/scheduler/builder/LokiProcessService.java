package org.yggdrasil.scheduler.builder;

/** Dedicated Android process entry point for LOKI. */
public final class LokiProcessService extends BuilderRoleProcessService {
    @Override protected BuilderRole role() { return BuilderRole.LOKI; }
}
