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
 * Minimal client for Gemini's OpenAI-compatible {@code /embeddings} endpoint (RB-3) — a
 * different path than {@link OpenAiCompatibleChatClient}'s {@code /chat/completions}, same base
 * URL/key. One call, short timeout, a plain float array back.
 */
@Component
class EmbeddingClient {

    private final ObjectMapper mapper = new ObjectMapper();

    /** The embedding vector for {@code text}, or throws on any HTTP/timeout/parse error. */
    List<Double> embed(AiProperties props, String text) {
        RestClient client = RestClient.builder()
                .baseUrl(props.getEmbeddingBaseUrl())
                .requestFactory(timeoutFactory(props.getEmbeddingTimeoutSeconds()))
                .build();

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", props.getEmbeddingModel());
        body.put("input", text);

        byte[] rawBytes;
        try {
            rawBytes = client.post()
                    .uri("/embeddings")
                    .header("Authorization", "Bearer " + props.getEmbeddingApiKey())
                    .header("Accept", "application/json")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .body(byte[].class);
        } catch (org.springframework.web.client.RestClientResponseException ex) {
            throw new IllegalStateException(props.getEmbeddingModel() + " embedding returned "
                    + ex.getStatusCode() + ": " + ex.getResponseBodyAsString(), ex);
        }

        String raw = rawBytes == null || rawBytes.length == 0
                ? null : new String(rawBytes, java.nio.charset.StandardCharsets.UTF_8);
        if (raw == null || raw.isBlank()) {
            throw new IllegalStateException("Empty embedding response from " + props.getEmbeddingModel());
        }
        EmbeddingResponse response;
        try {
            response = mapper.readValue(raw, EmbeddingResponse.class);
        } catch (Exception ex) {
            throw new IllegalStateException("Unparseable embedding response from "
                    + props.getEmbeddingModel(), ex);
        }
        if (response == null || response.data() == null || response.data().isEmpty()
                || response.data().get(0).embedding() == null) {
            throw new IllegalStateException("Malformed embedding response from " + props.getEmbeddingModel());
        }
        return response.data().get(0).embedding();
    }

    private static SimpleClientHttpRequestFactory timeoutFactory(long timeoutSeconds) {
        int millis = (int) Duration.ofSeconds(timeoutSeconds).toMillis();
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(millis);
        factory.setReadTimeout(millis);
        return factory;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record EmbeddingResponse(List<Item> data) {
        @JsonIgnoreProperties(ignoreUnknown = true)
        record Item(List<Double> embedding) {
        }
    }
}
