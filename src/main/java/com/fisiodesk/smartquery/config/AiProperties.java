package com.fisiodesk.smartquery.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration for the optional LLM integration. The client speaks the
 * OpenAI-compatible chat completions protocol, so the same code works against
 * OpenAI, Azure OpenAI, OpenRouter or a self-hosted Ollama instance; only
 * base-url/model/api-key change. When {@code enabled} is false the whole
 * system runs on the deterministic analyzer.
 */
@ConfigurationProperties(prefix = "smartquery.ai")
public record AiProperties(
        boolean enabled,
        String baseUrl,
        String apiKey,
        String model,
        int timeoutMs) {

    public AiProperties {
        if (baseUrl == null || baseUrl.isBlank()) {
            baseUrl = "https://api.openai.com/v1";
        }
        if (model == null || model.isBlank()) {
            model = "gpt-4o-mini";
        }
        if (timeoutMs <= 0) {
            timeoutMs = 20_000;
        }
    }
}
