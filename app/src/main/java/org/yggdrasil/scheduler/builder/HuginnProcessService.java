package org.yggdrasil.scheduler.builder;

/** Dedicated Android process entry point for HUGINN. */
public final class HuginnProcessService extends BuilderRoleProcessService {
    @Override protected BuilderRole role() { return BuilderRole.HUGINN; }
}
