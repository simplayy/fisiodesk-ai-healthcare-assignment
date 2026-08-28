package com.fisiodesk.smartquery.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Settings specific to this service; provider, model, timeouts and retries use
 * the standard Spring AI properties.
 *
 * @param enabled       false = rule-based analyzer and planner only
 * @param planTimeoutMs how long a request waits for the LLM planner before
 *                      answering with the rule-based plan
 */
@ConfigurationProperties(prefix = "smartquery.ai")
public record AiProperties(boolean enabled, int planTimeoutMs) {

    public AiProperties {
        if (planTimeoutMs <= 0) {
            planTimeoutMs = 1_500;
        }
    }
}
