package com.fisiodesk.smartquery.config;

import java.net.http.HttpClient;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class AiConfig {

    @Bean
    public ChatClient chatClient(ChatClient.Builder builder) {
        return builder.build();
    }

    /**
     * HTTP client for the LLM calls, still configured by the standard
     * {@code spring.http.client.*} properties (timeouts). HTTP/1.1 on purpose:
     * with HTTP/2 the JDK client multiplexes every call on one connection and
     * the provider answers the streams one after the other, so ten questions
     * planned at the same time came back ~60 ms apart in a staircase instead
     * of in parallel. With one connection per in-flight call (pooled and kept
     * alive) concurrent questions really run concurrently.
     */
    @Bean
    public ClientHttpRequestFactoryBuilder<?> clientHttpRequestFactoryBuilder() {
        return ClientHttpRequestFactoryBuilder.jdk()
                .withHttpClientCustomizer(client -> client.version(HttpClient.Version.HTTP_1_1));
    }

    /**
     * Pool for the LLM planning calls. Every new question runs here while the
     * request thread waits up to the planning budget, so the pool must be wide
     * enough that a burst of new questions does not queue behind each other:
     * with 4 threads and ~0.8 s per call, the fifth concurrent question would
     * already miss the budget. Calls are I/O bound, threads are cheap, and the
     * provider's rate limit is the real ceiling.
     */
    @Bean(destroyMethod = "shutdownNow")
    public ExecutorService llmExecutor() {
        return Executors.newFixedThreadPool(16, runnable -> {
            Thread thread = new Thread(runnable, "llm-worker");
            thread.setDaemon(true);
            return thread;
        });
    }
}
