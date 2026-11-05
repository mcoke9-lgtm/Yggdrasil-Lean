package org.yggdrasil.scheduler.builder;

import java.util.List;

/** Bridges the conversation coordinator to provider-neutral model adapters. */
public final class BuilderModelResponder implements BuilderConversationCoordinator.Responder {
    private final BuilderProviderRouter router;
    private final int maxOutputTokens;

    public BuilderModelResponder(BuilderProviderRouter router, int maxOutputTokens) {
        if (router == null) throw new IllegalArgumentException("Provider router is required");
        if (maxOutputTokens < 1 || maxOutputTokens > 32768)
            throw new IllegalArgumentException("Output token limit must be between 1 and 32768");
        this.router = router;
        this.maxOutputTokens = maxOutputTokens;
    }

    @Override public BuilderConversationCoordinator.Reply respond(
            BuilderRole role, List<BuilderConversationProtocol.Turn> threadContext,
            BuilderConversationProtocol.Turn triggeringTurn) throws Exception {
        BuilderPromptFactory.Prompt prompt = BuilderPromptFactory.forTurn(role, threadContext, triggeringTurn);
        BuilderModelProvider provider = router.providerFor(role);
        BuilderModelProvider.Request request = new BuilderModelProvider.Request(
                prompt.systemInstruction, prompt.userMessage, maxOutputTokens);
        BuilderModelProvider.Completion completion = provider.complete(request);
        if (completion == null) throw new IllegalStateException("Model provider returned no completion");
        return BuilderReplyParser.parse(completion.responseText);
    }
}
