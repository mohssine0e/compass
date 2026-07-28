package com.compass.app.resource;

import com.compass.app.ai.ResourceAiService;
import com.compass.app.ai.SearchGroundingService;
import com.compass.app.events.EventService;
import com.compass.app.resource.dto.EnrichmentResponse;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The resource enrichment cache (RES-1..RES-4 of RESSOURCE_BRAIN_TASKS.md): a "what to focus
 * on" for a written resource, or a real transcript-backed timestamp range for a video one,
 * cached forever per (resource url, topic) so the same resource is never re-processed twice.
 *
 * <p>{@link #cacheExaHighlights} is the zero-AI-call common case (RES-2), called inline from
 * {@link ResourceService} right when resources are suggested — piggybacking on a call that's
 * already being made. RES-3/RES-4's lazy fallback paths (fetch+summarize, transcript lookup)
 * live in this class too, but are only ever triggered from the deep view on demand — see
 * {@link ResourceController}.
 */
@Service
public class ResourceEnrichmentService {

    // Below this many characters, an Exa highlight reads as a fragment, not a usable "what to
    // focus on" — not worth caching as one; RES-3's fallback will produce a real pointer for it
    // instead, the first time the founder actually opens that step.
    private static final int MIN_HIGHLIGHT_LENGTH = 40;

    static final String KIND_WRITTEN = "written";
    static final String KIND_VIDEO = "video";
    static final String SOURCE_EXA_HIGHLIGHT = "exa_highlight";
    static final String SOURCE_FETCH_FALLBACK = "fetch_fallback";

    private final ResourceEnrichmentRepository repository;
    private final ResourcePageFetcher pageFetcher;
    private final ResourceAiService resourceAi;
    private final EventService events;

    public ResourceEnrichmentService(ResourceEnrichmentRepository repository, ResourcePageFetcher pageFetcher,
                                     ResourceAiService resourceAi, EventService events) {
        this.repository = repository;
        this.pageFetcher = pageFetcher;
        this.resourceAi = resourceAi;
        this.events = events;
    }

    /**
     * The lazy entry point (RES-3/RES-5): a cache hit returns instantly; a miss triggers the
     * appropriate fallback (fetch+summarize for a written resource; RES-4 adds the video path).
     * {@code null} when nothing could be produced — the deep view then shows the plain link with
     * no fabricated pointer, exactly as if enrichment had never been attempted.
     */
    @Transactional
    public EnrichmentResponse enrich(String resourceUrl, String stepTopic) {
        if (resourceUrl == null || resourceUrl.isBlank()) {
            return null;
        }
        String topicKey = topicKey(stepTopic);
        return repository.findByResourceUrlAndTopicKey(resourceUrl, topicKey)
                .map(ResourceEnrichmentService::toResponse)
                .orElseGet(() -> enrichWritten(resourceUrl, topicKey, stepTopic));
    }

    /**
     * RES-3: fetch the resource's real page, ask the Fast tier for a grounded focus pointer, and
     * cache it. A fetch failure or an AI call that couldn't produce a pointer both degrade to
     * "no enrichment" — no cache entry, plain link only — rather than anything fabricated.
     */
    private EnrichmentResponse enrichWritten(String resourceUrl, String topicKey, String stepTopic) {
        String pageText = pageFetcher.fetchText(resourceUrl);
        if (pageText == null) {
            events.systemError("resource_fetch_failed",
                    "Resource fetch failed for enrichment, showing plain link.", null);
            return null;
        }
        String pointer = resourceAi.focusPointer(stepTopic, pageText);
        if (pointer == null) {
            return null;
        }
        ResourceEnrichment enrichment = new ResourceEnrichment();
        enrichment.setResourceUrl(resourceUrl);
        enrichment.setTopicKey(topicKey);
        enrichment.setKind(KIND_WRITTEN);
        enrichment.setFocusPointer(pointer);
        enrichment.setSource(SOURCE_FETCH_FALLBACK);
        repository.save(enrichment);
        return toResponse(enrichment);
    }

    private static EnrichmentResponse toResponse(ResourceEnrichment e) {
        return new EnrichmentResponse(e.getKind(), e.getFocusPointer(), e.getSegmentStart(),
                e.getSegmentEnd(), e.getSegmentDescription(), e.getSource());
    }

    /**
     * RES-2: for each of {@code resources} that Exa returned a real highlight for (matched by
     * url against {@code groundingResults}), cache that highlight as the resource's focus
     * pointer for {@code stepTopic} — no AI call, no fetch, just surfacing something Exa had
     * already computed. A resource with no matching Exa highlight, or one already cached for
     * this exact topic, is silently skipped (the fallback path picks it up lazily instead).
     */
    @Transactional
    public void cacheExaHighlights(List<ResourceAiService.Resource> resources,
                                   List<SearchGroundingService.Result> groundingResults,
                                   String stepTopic) {
        if (resources == null || resources.isEmpty() || groundingResults == null || groundingResults.isEmpty()) {
            return;
        }
        Map<String, SearchGroundingService.Result> highlightsByUrl = groundingResults.stream()
                .filter(r -> r.isExaHighlight() && r.content() != null
                        && r.content().length() >= MIN_HIGHLIGHT_LENGTH)
                .collect(java.util.stream.Collectors.toMap(
                        SearchGroundingService.Result::url, r -> r, (a, b) -> a));
        if (highlightsByUrl.isEmpty()) {
            return;
        }
        String topicKey = topicKey(stepTopic);
        for (ResourceAiService.Resource resource : resources) {
            SearchGroundingService.Result highlight = highlightsByUrl.get(resource.url());
            if (highlight == null) {
                continue;
            }
            if (repository.findByResourceUrlAndTopicKey(resource.url(), topicKey).isPresent()) {
                continue;
            }
            ResourceEnrichment enrichment = new ResourceEnrichment();
            enrichment.setResourceUrl(resource.url());
            enrichment.setTopicKey(topicKey);
            enrichment.setKind(KIND_WRITTEN);
            enrichment.setFocusPointer(highlight.content());
            enrichment.setSource(SOURCE_EXA_HIGHLIGHT);
            repository.save(enrichment);
        }
    }

    /**
     * A short, stable key identifying the step/topic context an enrichment was generated for —
     * deliberately not a full free-text match, same reasoning and same shape as {@code
     * RoadmapService}'s own topic slugify (duplicated rather than shared across packages: three
     * lines, no real coupling gained from sharing it).
     */
    static String topicKey(String text) {
        if (text == null) {
            return "";
        }
        String slug = text.trim().toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("(^-|-$)", "");
        return slug.length() <= 128 ? slug : slug.substring(0, 128);
    }
}
