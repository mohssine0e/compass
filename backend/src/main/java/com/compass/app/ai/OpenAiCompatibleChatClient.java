package com.compass.app.ai;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * Minimal client for any OpenAI-compatible {@code /chat/completions} endpoint (Gemini,
 * Groq, DeepSeek, Ollama, …). One call, short timeout, plain text back.
 */
@Component
class OpenAiCompatibleChatClient {

    /**
     * Single completion. Returns the assistant's text, or throws on any HTTP/timeout error
     * so the caller can fail over to another provider.
     */
    String complete(AiProperties.Provider provider, long timeoutSeconds, int maxTokens,
                    String system, String user) {
        RestClient client = RestClient.builder()
                .baseUrl(provider.getBaseUrl())
                .requestFactory(timeoutFactory(timeoutSeconds))
                .build();

        Map<String, Object> body = Map.of(
                "model", provider.getModel(),
                "max_tokens", maxTokens,
                "messages", List.of(
                        Map.of("role", "system", "content", system),
                        Map.of("role", "user", "content", user)));

        ChatResponse response = client.post()
                .uri("/chat/completions")
                .header("Authorization", "Bearer " + provider.getApiKey())
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .body(ChatResponse.class);

        if (response == null || response.choices() == null || response.choices().isEmpty()) {
            return null;
        }
        ChatResponse.Choice choice = response.choices().get(0);
        return choice.message() != null ? choice.message().content() : null;
    }

    private static SimpleClientHttpRequestFactory timeoutFactory(long timeoutSeconds) {
        int millis = (int) Duration.ofSeconds(timeoutSeconds).toMillis();
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(millis);
        factory.setReadTimeout(millis);
        return factory;
    }

    // Only the fields we read; everything else in the OpenAI response is ignored.
    @JsonIgnoreProperties(ignoreUnknown = true)
    record ChatResponse(List<Choice> choices) {
        @JsonIgnoreProperties(ignoreUnknown = true)
        record Choice(Message message) {
        }

        @JsonIgnoreProperties(ignoreUnknown = true)
        record Message(String role, String content) {
        }
    }
}
