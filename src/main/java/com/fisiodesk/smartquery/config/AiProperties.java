package com.fisiodesk.smartquery.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Switches for the optional LLM integration. Provider, model, credentials,
 * timeouts and retries are configured through the standard Spring AI
 * properties ({@code spring.ai.openai.*}, {@code spring.ai.retry.*},
 * {@code spring.http.client.*}); this only holds what is specific to this
 * service.
 *
 * @param enabled       when false the system runs entirely on the rule-based analyzer
 * @param planTimeoutMs how long a user request waits for the LLM planner
 *                      before answering with the rule-based plan instead
 */
@ConfigurationProperties(prefix = "smartquery.ai")
public record AiProperties(boolean enabled, int planTimeoutMs) {

    public AiProperties {
        if (planTimeoutMs <= 0) {
            planTimeoutMs = 1_500;
        }
    }
}
