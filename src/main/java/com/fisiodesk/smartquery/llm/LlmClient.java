package com.fisiodesk.smartquery.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fisiodesk.smartquery.config.AiProperties;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Minimal chat-completions client (OpenAI wire protocol). Kept dependency-free
 * on purpose: this MVP needs exactly one endpoint, and owning the ~50 lines
 * gives full control over timeouts and error handling.
 */
@Component
public class LlmClient {

    private static final Logger log = LoggerFactory.getLogger(LlmClient.class);

    private final AiProperties properties;
    private final ObjectMapper objectMapper;
    private final RestClient restClient;

    public LlmClient(AiProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(Duration.ofSeconds(5));
        requestFactory.setReadTimeout(Duration.ofMillis(properties.timeoutMs()));
        this.restClient = RestClient.builder()
                .baseUrl(properties.baseUrl())
                .requestFactory(requestFactory)
                .build();
    }

    public boolean isEnabled() {
        return properties.enabled();
    }

    public String model() {
        return properties.model();
    }

    /**
     * Sends a system+user prompt and returns the raw text content of the first
     * choice. Callers ask for JSON and parse it themselves; any transport or
     * parsing problem surfaces as LlmException so callers can fall back to the
     * heuristic path.
     */
    public String complete(String systemPrompt, String userPrompt) {
        Map<String, Object> body = Map.of(
                "model", properties.model(),
                "temperature", 0,
                "response_format", Map.of("type", "json_object"),
                "messages", List.of(
                        Map.of("role", "system", "content", systemPrompt),
                        Map.of("role", "user", "content", userPrompt)));
        try {
            String response = restClient.post()
                    .uri("/chat/completions")
                    .header("Authorization", "Bearer " + apiKeyOrPlaceholder())
                    .header("Content-Type", "application/json")
                    .body(body)
                    .retrieve()
                    .body(String.class);
            JsonNode root = objectMapper.readTree(response);
            JsonNode content = root.path("choices").path(0).path("message").path("content");
            if (content.isMissingNode() || content.asText().isBlank()) {
                throw new LlmException("Empty completion from model " + properties.model());
            }
            return content.asText();
        } catch (LlmException e) {
            throw e;
        } catch (Exception e) {
            log.warn("LLM call to {} failed: {}", properties.baseUrl(), e.getMessage());
            throw new LlmException("LLM call failed: " + e.getMessage(), e);
        }
    }

    private String apiKeyOrPlaceholder() {
        // Local servers (e.g. Ollama) ignore the key but the header must be present.
        return properties.apiKey() == null || properties.apiKey().isBlank()
                ? "not-needed" : properties.apiKey();
    }
}
