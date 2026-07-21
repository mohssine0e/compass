package com.compass.app.ai;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Minimal client for any OpenAI-compatible {@code /chat/completions} endpoint (Gemini,
 * Groq, NVIDIA NIM, …). One call, short timeout, plain text back.
 */
@Component
class OpenAiCompatibleChatClient {

    // The raw body is parsed with our own mapper rather than RestClient's automatic
    // message-converter dispatch: some OpenAI-compatible gateways (observed on NVIDIA's NIM
    // catalog) answer with a Content-Type Spring has no JSON converter registered for (e.g.
    // application/octet-stream) even though the body is plain JSON — fetching as a String and
    // parsing it ourselves sidesteps that content-type mismatch entirely.
    private final ObjectMapper mapper = new ObjectMapper();

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

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", provider.getModel());
        body.put("max_tokens", maxTokens);
        body.put("messages", List.of(
                Map.of("role", "system", "content", system),
                Map.of("role", "user", "content", user)));
        // NIM reasoning models (e.g. NVIDIA's Nemotron Super) spend a chunk of max_tokens on an
        // internal "thinking" trace before the real answer; skip it since only the final JSON
        // matters here, not the reasoning that produced it.
        if (provider.isDisableThinking()) {
            body.put("chat_template_kwargs", Map.of("thinking", false));
        }

        byte[] rawBytes;
        try {
            rawBytes = client.post()
                    .uri("/chat/completions")
                    .header("Authorization", "Bearer " + provider.getApiKey())
                    .header("Accept", "application/json")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .body(byte[].class);
        } catch (org.springframework.web.client.RestClientResponseException ex) {
            // Surface the real status + body so a 4xx/5xx from the provider (e.g. a rate-limit
            // page) is diagnosable instead of hiding behind a generic conversion-error message.
            throw new IllegalStateException(provider.getModel() + " returned " + ex.getStatusCode()
                    + ": " + ex.getResponseBodyAsString(), ex);
        }

        String raw = rawBytes == null || rawBytes.length == 0
                ? null : new String(rawBytes, java.nio.charset.StandardCharsets.UTF_8);
        if (raw == null || raw.isBlank()) {
            return null;
        }
        ChatResponse response;
        try {
            response = mapper.readValue(raw, ChatResponse.class);
        } catch (Exception ex) {
            throw new IllegalStateException("Unparseable response from " + provider.getModel(), ex);
        }

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
