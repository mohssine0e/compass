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
import java.util.concurrent.ConcurrentHashMap;

/**
 * Grounds roadmap generation in real search results (Phase 7). A generic chat
 * model has no live
 * web access, so before drafting a roadmap we fetch a few results about the
 * goal (official docs,
 * established curricula), feed them in as context, and surface the sources so
 * the founder can
 * sanity-check the draft, not just trust it.
 *
 * <p>
 * Providers are tried in order with automatic failover, same primary/fallback
 * pattern as the
 * chat models: <b>Exa</b> ({@code EXA_API_KEY}) primary, <b>Tavily</b>
 * ({@code SEARCH_API_KEY}) fallback. Each normalizes to the same
 * {@link Result} shape, and
 * the next is tried on error or empty results. A small in-memory TTL cache
 * keyed on the query
 * avoids re-spending quota when the same goal is searched again (mainly a dev
 * saver). Entirely
 * optional and best-effort: with no key configured or on failure,
 * {@link #ground} returns
 * {@code null} and generation proceeds ungrounded.
 *
 * <p>
 * Call budget: one {@code ground} call per roadmap proposal (on the goal) —
 * resource discovery
 * reuses that single result set rather than searching per step.
 */
@Service
public class SearchGroundingService {

    private static final Logger log = LoggerFactory.getLogger(SearchGroundingService.class);
    private static final int MAX_CACHE_ENTRIES = 256;

    private final String exaBaseUrl;
    private final String exaApiKey;
    private final String tavilyBaseUrl;
    private final String tavilyApiKey;
    private final int maxResults;
    private final int timeoutSeconds;
    private final long cacheTtlMillis;
    private final EventService events;

    // Query (normalized) → grounding, with an expiry. Bounded and TTL'd; fine for a
    // single user.
    private final Map<String, Cached> cache = new ConcurrentHashMap<>();

    public SearchGroundingService(
            @Value("${compass.search.exa.base-url:https://api.exa.ai}") String exaBaseUrl,
            @Value("${compass.search.exa.api-key:}") String exaApiKey,
            @Value("${compass.search.tavily.base-url:https://api.tavily.com}") String tavilyBaseUrl,
            @Value("${compass.search.tavily.api-key:}") String tavilyApiKey,
            @Value("${compass.search.max-results:8}") int maxResults,
            @Value("${compass.search.timeout-seconds:8}") int timeoutSeconds,
            @Value("${compass.search.cache-ttl-seconds:3600}") long cacheTtlSeconds,
            EventService events) {
        this.exaBaseUrl = exaBaseUrl;
        this.exaApiKey = exaApiKey;
        this.tavilyBaseUrl = tavilyBaseUrl;
        this.tavilyApiKey = tavilyApiKey;
        this.maxResults = maxResults;
        this.timeoutSeconds = timeoutSeconds;
        this.cacheTtlMillis = cacheTtlSeconds * 1000L;
        this.events = events;
    }

    private boolean exaConfigured() {
        return exaApiKey != null && !exaApiKey.isBlank();
    }

    private boolean tavilyConfigured() {
        return tavilyApiKey != null && !tavilyApiKey.isBlank();
    }

    /**
     * True when at least one provider is configured — otherwise grounding is
     * skipped entirely.
     */
    public boolean isEnabled() {
        return exaConfigured() || tavilyConfigured();
    }

    /**
     * Search results about {@code query} as grounding, or {@code null} when
     * disabled or on any
     * failure (generation then proceeds ungrounded). Tries Exa, then Tavily;
     * caches a hit.
     * Never throws into the caller.
     */
    public Grounding ground(String query) {
        if (!isEnabled() || query == null || query.isBlank()) {
            return null;
        }
        String key = query.trim().toLowerCase();

        Cached hit = cache.get(key);
        long now = System.currentTimeMillis();
        if (hit != null && hit.expiresAt() > now) {
            return hit.grounding();
        }

        List<Result> results = fetch(query.trim());
        if (results == null || results.isEmpty()) {
            return null;
        }
        Grounding grounding = toGrounding(results);
        if (grounding != null && cacheTtlMillis > 0) {
            if (cache.size() >= MAX_CACHE_ENTRIES) {
                cache.clear();
            }
            cache.put(key, new Cached(grounding, now + cacheTtlMillis));
        }
        return grounding;
    }

    /**
     * Exa → Tavily, moving on when one errors or comes back empty (each
     * logs its own).
     */
    private List<Result> fetch(String query) {
        List<Result> results = exaConfigured() ? exa(query) : null;
        if ((results == null || results.isEmpty()) && tavilyConfigured()) {
            results = tavily(query);
        }
        return results;
    }

    private List<Result> exa(String query) {
        try {
            RestClient client = RestClient.builder()
                    .baseUrl(exaBaseUrl)
                    .requestFactory(timeoutFactory(timeoutSeconds))
                    .build();
            ExaResponse response = client.post()
                    .uri(URI.create(exaBaseUrl + "/search"))
                    .header("x-api-key", exaApiKey)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of(
                            "query", query,
                            "type", "auto",
                            "numResults", maxResults,
                            "contents", Map.of("highlights", true)))
                    .retrieve()
                    .body(ExaResponse.class);
            if (response == null || response.results() == null) {
                return null;
            }
            List<Result> results = new ArrayList<>();
            for (ExaResponse.ExaResult r : response.results()) {
                if (r.title() != null && !r.title().isBlank()) {
                    String snippet = r.highlights() == null || r.highlights().isEmpty()
                            ? ""
                            : String.join(" … ", r.highlights());
                    results.add(new Result(r.title().strip(),
                            r.url() == null ? "" : r.url().strip(), snippet.strip()));
                }
            }
            return results;
        } catch (RuntimeException ex) {
            log.warn("Exa search failed: {}", ex.getMessage());
            events.aiWarning("search_error", "Exa search failed, trying fallback: "
                    + AiFailures.reason(ex), null);
            return null;
        }
    }

    private List<Result> tavily(String query) {
        try {
            RestClient client = RestClient.builder()
                    .baseUrl(tavilyBaseUrl)
                    .requestFactory(timeoutFactory(timeoutSeconds))
                    .build();
            TavilyResponse response = client.post()
                    .uri(URI.create(tavilyBaseUrl + "/search"))
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of(
                            "api_key", tavilyApiKey,
                            "query", query,
                            "max_results", maxResults,
                            "search_depth", "basic"))
                    .retrieve()
                    .body(TavilyResponse.class);
            if (response == null || response.results() == null) {
                return null;
            }
            List<Result> results = new ArrayList<>();
            for (TavilyResponse.TavilyResult r : response.results()) {
                if (r.title() != null && !r.title().isBlank()) {
                    results.add(new Result(r.title().strip(), r.url() == null ? "" : r.url().strip(),
                            r.content() == null ? "" : r.content().strip()));
                }
            }
            return results;
        } catch (RuntimeException ex) {
            log.warn("Tavily search failed: {}", ex.getMessage());
            events.aiWarning("search_error", "Search grounding failed; drafted ungrounded: "
                    + AiFailures.reason(ex), null);
            return null;
        }
    }

    private static Grounding toGrounding(List<Result> raw) {
        StringBuilder context = new StringBuilder();
        List<String> sources = new ArrayList<>();
        List<Result> results = new ArrayList<>();
        for (Result r : raw) {
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
            String url = r.url() == null ? "" : r.url().strip();
            sources.add(!url.isBlank() ? r.title().strip() + " — " + host(url) : r.title().strip());
            results.add(new Result(r.title().strip(), url, snippet));
        }
        if (sources.isEmpty()) {
            return null;
        }
        return new Grounding(context.toString().strip(), sources, results);
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

    private record Cached(Grounding grounding, long expiresAt) {
    }

    /**
     * Grounding for a generation: snippet {@code context} for the prompt,
     * {@code sources} to
     * show, and the raw {@code results} (with real URLs) that resource discovery
     * draws from.
     */
    public record Grounding(String context, List<String> sources, List<Result> results) {
    }

    /**
     * One real search result — its URL is what resource suggestions are allowed to
     * link to.
     */
    public record Result(String title, String url, String content) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record ExaResponse(List<ExaResult> results) {
        @JsonIgnoreProperties(ignoreUnknown = true)
        record ExaResult(String title, String url, List<String> highlights) {
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record TavilyResponse(List<TavilyResult> results) {
        @JsonIgnoreProperties(ignoreUnknown = true)
        record TavilyResult(String title, String url, String content) {
        }
    }
}
