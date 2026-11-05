package org.yggdrasil.scheduler.builder;

import java.util.List;

/** Bridges the conversation coordinator to provider-neutral model adapters. */
public final class BuilderModelResponder implements BuilderConversationCoordinator.Responder {
    private final BuilderProviderRouter router;
    private final int maxOutputTokens;
    private final String operationalLimitsJson;

    public BuilderModelResponder(BuilderProviderRouter router, int maxOutputTokens) {
        this(router, maxOutputTokens,
                "{\\"register_status\\":\\"UNAVAILABLE\\",\\"live_telemetry\\":false,"
                        + "\\"unlisted_capability_decision\\":\\"RECHECK_REQUIRED\\"}");
    }

    public BuilderModelResponder(BuilderProviderRouter router, int maxOutputTokens,
                                 String operationalLimitsJson) {
        if (router == null) throw new IllegalArgumentException("Provider router is required");
        if (maxOutputTokens < 1 || maxOutputTokens > 32768)
            throw new IllegalArgumentException("Output token limit must be between 1 and 32768");
        this.router = router;
        this.maxOutputTokens = maxOutputTokens;
        this.operationalLimitsJson = operationalLimitsJson == null ? "" : operationalLimitsJson;
    }

    @Override public BuilderConversationCoordinator.Reply respond(
            BuilderRole role, List<BuilderConversationProtocol.Turn> threadContext,
            BuilderConversationProtocol.Turn triggeringTurn) throws Exception {
        if (threadContext == null || threadContext.isEmpty())
            throw new IllegalArgumentException("Conversation context must contain the triggering turn");
        // Later Builders receive earlier Builder replies too. The prompt factory
        // requires its trigger to be the latest turn in the accumulated context.
        BuilderConversationProtocol.Turn latestTurn = threadContext.get(threadContext.size() - 1);
        BuilderPromptFactory.Prompt prompt = BuilderPromptFactory.forTurn(
                role, threadContext, latestTurn, operationalLimitsJson);
        BuilderModelProvider provider = router.providerFor(role);
        BuilderModelProvider.Request request = new BuilderModelProvider.Request(
                prompt.systemInstruction, prompt.userMessage, maxOutputTokens);
        BuilderModelProvider.Completion completion = provider.complete(request);
        if (completion == null) throw new IllegalStateException("Model provider returned no completion");
        return BuilderReplyParser.parse(completion.responseText);
    }
}
