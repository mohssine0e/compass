package com.compass.app.ai;

import com.compass.app.events.EventService;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Grounds roadmap generation in real search results (Phase 7). A generic chat model has no live
 * web access, so before drafting a roadmap we fetch a few results about the goal (official docs,
 * established curricula) and feed them in as context — and surface the sources so the founder can
 * sanity-check the draft, not just trust it.
 *
 * <p>Uses a dedicated search API (Tavily by default), separate from the Gemini/Groq chat models,
 * configured with its own {@code SEARCH_API_KEY}. Entirely optional and best-effort: when no key
 * is set or the call fails, {@link #ground} returns {@code null} and generation proceeds
 * ungrounded rather than failing.
 */
@Service
public class SearchGroundingService {

    private static final Logger log = LoggerFactory.getLogger(SearchGroundingService.class);

    private final String baseUrl;
    private final String apiKey;
    private final int maxResults;
    private final int timeoutSeconds;
    private final EventService events;

    public SearchGroundingService(
            @Value("${compass.search.base-url:https://api.tavily.com}") String baseUrl,
            @Value("${compass.search.api-key:}") String apiKey,
            @Value("${compass.search.max-results:5}") int maxResults,
            @Value("${compass.search.timeout-seconds:8}") int timeoutSeconds,
            EventService events) {
        this.baseUrl = baseUrl;
        this.apiKey = apiKey;
        this.maxResults = maxResults;
        this.timeoutSeconds = timeoutSeconds;
        this.events = events;
    }

    /** True when a search key is configured — otherwise grounding is skipped entirely. */
    public boolean isEnabled() {
        return apiKey != null && !apiKey.isBlank();
    }

    /**
     * Search results about {@code query} as grounding, or {@code null} when disabled or on any
     * failure (generation then proceeds ungrounded). Never throws into the caller.
     */
    public Grounding ground(String query) {
        if (!isEnabled() || query == null || query.isBlank()) {
            return null;
        }
        try {
            RestClient client = RestClient.builder()
                    .baseUrl(baseUrl)
                    .requestFactory(timeoutFactory(timeoutSeconds))
                    .build();

            TavilyResponse response = client.post()
                    .uri(URI.create(baseUrl + "/search"))
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of(
                            "api_key", apiKey,
                            "query", query.trim(),
                            "max_results", maxResults,
                            "search_depth", "basic"))
                    .retrieve()
                    .body(TavilyResponse.class);

            if (response == null || response.results() == null || response.results().isEmpty()) {
                return null;
            }
            return toGrounding(response.results());
        } catch (RuntimeException ex) {
            log.warn("Search grounding failed: {}", ex.getMessage());
            events.aiWarning("search_error", "Search grounding failed; drafted ungrounded: "
                    + AiFailures.reason(ex), null);
            return null;
        }
    }

    private static Grounding toGrounding(List<TavilyResponse.Result> results) {
        StringBuilder context = new StringBuilder();
        List<String> sources = new ArrayList<>();
        for (TavilyResponse.Result r : results) {
            if (r.title() == null || r.title().isBlank()) {
                continue;
            }
            String snippet = r.content() == null ? "" : r.content().strip();
            if (snippet.length() > 400) {
                snippet = snippet.substring(0, 400) + "…";
            }
            context.append("- ").append(r.title().strip());
            if (!snippet.isEmpty()) {
                context.append(": ").append(snippet);
            }
            context.append('\n');
            sources.add(r.url() != null && !r.url().isBlank()
                    ? r.title().strip() + " — " + host(r.url())
                    : r.title().strip());
        }
        if (sources.isEmpty()) {
            return null;
        }
        return new Grounding(context.toString().strip(), sources);
    }

    private static String host(String url) {
        try {
            String h = URI.create(url).getHost();
            return h == null ? url : h.replaceFirst("^www\\.", "");
        } catch (RuntimeException ex) {
            return url;
        }
    }

    private static SimpleClientHttpRequestFactory timeoutFactory(int timeoutSeconds) {
        int millis = (int) Duration.ofSeconds(timeoutSeconds).toMillis();
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(millis);
        factory.setReadTimeout(millis);
        return factory;
    }

    /** Grounding for a generation: snippet {@code context} for the prompt, {@code sources} to show. */
    public record Grounding(String context, List<String> sources) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record TavilyResponse(List<Result> results) {
        @JsonIgnoreProperties(ignoreUnknown = true)
        record Result(String title, String url, String content) {
        }
    }
}
