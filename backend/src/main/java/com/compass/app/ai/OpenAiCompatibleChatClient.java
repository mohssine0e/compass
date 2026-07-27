package com.compass.app.ai;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Minimal client for any OpenAI-compatible {@code /chat/completions} endpoint (Gemini, Groq,
 * NVIDIA NIM, …). One call, short timeout, plain text back.
 *
 * <p>Clients are built once and cached, over a single shared {@link HttpClient} that owns the
 * connection pool. The previous version called {@code RestClient.builder()...build()} inside
 * every request with a {@code SimpleClientHttpRequestFactory} (JDK {@code HttpURLConnection},
 * no pooling), so every AI call paid a fresh TCP connect and TLS handshake to a remote API —
 * on the order of 100–300ms, on a path where the whole fast-tier budget is 6 seconds.
 */
@Component
class OpenAiCompatibleChatClient {

    // The raw body is parsed with our own mapper rather than RestClient's automatic
    // message-converter dispatch: some OpenAI-compatible gateways (observed on NVIDIA's NIM
    // catalog) answer with a Content-Type Spring has no JSON converter registered for (e.g.
    // application/octet-stream) even though the body is plain JSON — fetching as a String and
    // parsing it ourselves sidesteps that content-type mismatch entirely.
    private final ObjectMapper mapper = new ObjectMapper();

    // One pool for every provider. Connect timeout lives here; the per-call read timeout is set
    // on the request factory, which is why clients are keyed by timeout as well as by provider.
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    private final Map<String, RestClient> clients = new ConcurrentHashMap<>();

    /**
     * Single completion. Returns the assistant's text, or throws {@link AiCallException} on any
     * HTTP/timeout error so the caller can bench the provider and fail over to another.
     */
    String complete(AiProperties.Provider provider, long timeoutSeconds, int maxTokens,
                    String system, String user) {
        RestClient client = clientFor(provider, timeoutSeconds);

        // NIM reasoning models (e.g. NVIDIA's Nemotron Super) spend a chunk of max_tokens on an
        // internal "thinking" trace before the real answer; skip it since only the final JSON
        // matters here, not the reasoning that produced it. Observed live: the flag alone can be
        // silently ignored (reasoning still fills the whole budget, leaving `content` null and
        // `finish_reason: length`) — so the model card's own "/no_think" system-prompt directive
        // is added too, belt-and-suspenders, not just the API kwarg.
        String effectiveSystem = provider.isDisableThinking() ? system + "\n/no_think" : system;

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", provider.getModel());
        body.put("max_tokens", maxTokens);
        body.put("messages", List.of(
                Map.of("role", "system", "content", effectiveSystem),
                Map.of("role", "user", "content", user)));
        if (provider.isDisableThinking()) {
            // The correct key is "enable_thinking", not "thinking" — the wrong key name was
            // silently ignored by the API (no error, just thinking left on), which is why this
            // went unnoticed until a live module-expansion call came back with content: null.
            body.put("chat_template_kwargs", Map.of("enable_thinking", false));
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
            // Carry the real status through so the circuit breaker can tell a 429 (back off) from
            // a 401 (bad key, say it once) from a 500 (blip) — the body is kept in the message so
            // it's still diagnosable, e.g. a rate-limit page naming the reset window.
            int status = ex.getStatusCode().value();
            throw new AiCallException(AiCallException.kindForStatus(status), status,
                    provider.getModel() + " returned " + status + ": " + ex.getResponseBodyAsString(), ex);
        } catch (org.springframework.web.client.ResourceAccessException ex) {
            // Connect/read timeouts and other I/O failures surface here.
            throw new AiCallException(AiCallException.kindOf(ex), null,
                    provider.getModel() + " unreachable: " + AiFailures.reason(ex), ex);
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
            // The provider answered — this is our problem, not its availability. BAD_RESPONSE
            // deliberately does not bench it.
            throw new AiCallException(AiCallException.Kind.BAD_RESPONSE, null,
                    "Unparseable response from " + provider.getModel(), ex);
        }

        if (response == null || response.choices() == null || response.choices().isEmpty()) {
            return null;
        }
        ChatResponse.Choice choice = response.choices().get(0);
        return choice.message() != null ? choice.message().content() : null;
    }

    // Keyed by provider *and* timeout: the read timeout is fixed per factory, and the same
    // provider is called with different budgets (fast-json vs generation vs skeleton). All of
    // them share the one HttpClient above, so this multiplies factories, never connection pools.
    private RestClient clientFor(AiProperties.Provider provider, long timeoutSeconds) {
        String key = provider.getName() + "|" + provider.getBaseUrl() + "|" + timeoutSeconds;
        return clients.computeIfAbsent(key, k -> {
            JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(httpClient);
            factory.setReadTimeout(Duration.ofSeconds(timeoutSeconds));
            return RestClient.builder()
                    .baseUrl(provider.getBaseUrl())
                    .requestFactory(factory)
                    .build();
        });
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
