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
     * HTTP/1.1 on purpose: with HTTP/2 the JDK client multiplexes all calls on
     * one connection and the provider serves them one after the other, so
     * concurrent questions were not actually concurrent.
     */
    @Bean
    public ClientHttpRequestFactoryBuilder<?> clientHttpRequestFactoryBuilder() {
        return ClientHttpRequestFactoryBuilder.jdk()
                .withHttpClientCustomizer(client -> client.version(HttpClient.Version.HTTP_1_1));
    }

    /**
     * Pool for the LLM planning calls, wide enough that a burst of new
     * questions does not queue: with 4 threads and ~0.8 s per call the fifth
     * concurrent question would already miss the budget.
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
