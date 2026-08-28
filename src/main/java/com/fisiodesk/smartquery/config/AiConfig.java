package com.fisiodesk.smartquery.config;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class AiConfig {

    @Bean
    public ChatClient chatClient(ChatClient.Builder builder) {
        return builder.build();
    }

    /**
     * Small pool for LLM calls made outside the request thread (planning that
     * exceeded its latency budget, warm-up). Calls are I/O bound and the
     * provider has its own concurrency limits, so a handful of threads is enough.
     */
    @Bean(destroyMethod = "shutdownNow")
    public ExecutorService llmExecutor() {
        return Executors.newFixedThreadPool(4, runnable -> {
            Thread thread = new Thread(runnable, "llm-worker");
            thread.setDaemon(true);
            return thread;
        });
    }
}
