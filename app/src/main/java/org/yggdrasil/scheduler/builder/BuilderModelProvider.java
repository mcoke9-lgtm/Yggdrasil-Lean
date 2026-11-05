package org.yggdrasil.scheduler.builder;

/**
 * Provider-neutral model boundary. Implementations may call a hosted API or a
 * locally hosted model, but must return raw response text for strict parsing.
 * Implementations must never log credentials or full sensitive prompts.
 */
public interface BuilderModelProvider {
    String providerId();

    Completion complete(Request request) throws Exception;

    final class Request {
        public final String systemInstruction;
        public final String userMessage;
        public final int maxOutputTokens;

        public Request(String systemInstruction, String userMessage, int maxOutputTokens) {
            if (systemInstruction == null || systemInstruction.trim().isEmpty())
                throw new IllegalArgumentException("System instruction is required");
            if (userMessage == null || userMessage.trim().isEmpty())
                throw new IllegalArgumentException("User message is required");
            if (maxOutputTokens < 1 || maxOutputTokens > 32768)
                throw new IllegalArgumentException("Output token limit must be between 1 and 32768");
            this.systemInstruction = systemInstruction;
            this.userMessage = userMessage;
            this.maxOutputTokens = maxOutputTokens;
        }
    }

    final class Completion {
        public final String responseText;
        public final String modelId;

        public Completion(String responseText, String modelId) {
            if (responseText == null || responseText.trim().isEmpty())
                throw new IllegalArgumentException("Provider response must not be blank");
            this.responseText = responseText;
            this.modelId = modelId == null ? "" : modelId;
        }
    }
}
