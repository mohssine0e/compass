package com.compass.app.resource;

import com.compass.app.ai.ResourceAiService;
import com.compass.app.ai.SearchGroundingService;
import com.compass.app.events.EventService;
import com.compass.app.resource.dto.EnrichmentResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * RES-2/RES-3: caching a real Exa highlight as a resource's focus pointer costs no extra AI call
 * and no extra fetch — it's just surfacing something already computed. Pins down the actual
 * matching/threshold/dedup rules, since those are exactly the kind of thing that silently does
 * nothing if a condition is subtly wrong (matches V3-2.7's own lesson about this codebase). The
 * fallback fetch+summarize path is covered too, including the graceful-degradation cases: a
 * failed fetch or an AI call with nothing useful to say both mean "plain link only," never a
 * fabricated pointer.
 */
class ResourceEnrichmentServiceTest {

    private ResourceEnrichmentRepository repository;
    private ResourcePageFetcher pageFetcher;
    private ResourceAiService resourceAi;
    private EventService events;
    private ResourceEnrichmentService service;

    @BeforeEach
    void setUp() {
        repository = mock(ResourceEnrichmentRepository.class);
        pageFetcher = mock(ResourcePageFetcher.class);
        resourceAi = mock(ResourceAiService.class);
        events = mock(EventService.class);
        service = new ResourceEnrichmentService(repository, pageFetcher, resourceAi, events);
    }

    private static ResourceAiService.Resource resource(String url) {
        return new ResourceAiService.Resource("Title", url, "written", "official_docs", "~20 min", null);
    }

    private static SearchGroundingService.Result exaHighlight(String url, String text) {
        return new SearchGroundingService.Result("Title", url, text, true);
    }

    private static SearchGroundingService.Result tavilySnippet(String url, String text) {
        return new SearchGroundingService.Result("Title", url, text, false);
    }

    @Test
    @DisplayName("a resource whose grounding result carries a real Exa highlight gets it cached")
    void cachesMatchingExaHighlight() {
        when(repository.findByResourceUrlAndTopicKey(any(), any())).thenReturn(Optional.empty());
        String longHighlight = "A genuinely long extractive quote pulled straight from the page, well past the minimum length.";

        service.cacheExaHighlights(
                List.of(resource("https://example.com/a")),
                List.of(exaHighlight("https://example.com/a", longHighlight)),
                "Ownership & Borrowing");

        ArgumentCaptor<ResourceEnrichment> captor = ArgumentCaptor.forClass(ResourceEnrichment.class);
        verify(repository).save(captor.capture());
        ResourceEnrichment saved = captor.getValue();
        assertThat(saved.getResourceUrl()).isEqualTo("https://example.com/a");
        assertThat(saved.getTopicKey()).isEqualTo("ownership-borrowing");
        assertThat(saved.getKind()).isEqualTo("written");
        assertThat(saved.getFocusPointer()).isEqualTo(longHighlight);
        assertThat(saved.getSource()).isEqualTo("exa_highlight");
    }

    @Test
    @DisplayName("a Tavily content snippet is never cached as an Exa highlight, even if it's long")
    void neverCachesTavilySnippetAsHighlight() {
        service.cacheExaHighlights(
                List.of(resource("https://example.com/a")),
                List.of(tavilySnippet("https://example.com/a",
                        "A long Tavily paraphrase that reads nothing like an extractive quote but is definitely long enough.")),
                "topic");

        verify(repository, never()).save(any());
    }

    @Test
    @DisplayName("a too-short Exa highlight is left for the fallback path instead of cached as a fragment")
    void skipsHighlightBelowMinimumLength() {
        service.cacheExaHighlights(
                List.of(resource("https://example.com/a")),
                List.of(exaHighlight("https://example.com/a", "Too short.")),
                "topic");

        verify(repository, never()).save(any());
    }

    @Test
    @DisplayName("a resource with no matching grounding result (different url) is skipped")
    void skipsResourceWithNoMatchingGroundingResult() {
        service.cacheExaHighlights(
                List.of(resource("https://example.com/not-in-grounding")),
                List.of(exaHighlight("https://example.com/a", "A long enough highlight for the threshold check to pass cleanly.")),
                "topic");

        verify(repository, never()).save(any());
    }

    @Test
    @DisplayName("already-cached (url, topic) pairs are not re-saved")
    void skipsAlreadyCachedPair() {
        when(repository.findByResourceUrlAndTopicKey("https://example.com/a", "topic"))
                .thenReturn(Optional.of(new ResourceEnrichment()));

        service.cacheExaHighlights(
                List.of(resource("https://example.com/a")),
                List.of(exaHighlight("https://example.com/a", "A long enough highlight for the threshold check to pass cleanly.")),
                "topic");

        verify(repository, never()).save(any());
    }

    @Test
    @DisplayName("the same resource is cached separately per distinct topic")
    void cachesSeparatelyPerTopic() {
        when(repository.findByResourceUrlAndTopicKey(any(), any())).thenReturn(Optional.empty());
        String highlight = "A long enough highlight for the threshold check to pass cleanly every time.";
        List<SearchGroundingService.Result> grounding = List.of(exaHighlight("https://example.com/a", highlight));

        service.cacheExaHighlights(List.of(resource("https://example.com/a")), grounding, "Ownership");
        service.cacheExaHighlights(List.of(resource("https://example.com/a")), grounding, "Borrowing");

        ArgumentCaptor<ResourceEnrichment> captor = ArgumentCaptor.forClass(ResourceEnrichment.class);
        verify(repository, org.mockito.Mockito.times(2)).save(captor.capture());
        assertThat(captor.getAllValues()).extracting(ResourceEnrichment::getTopicKey)
                .containsExactly("ownership", "borrowing");
    }

    @Test
    @DisplayName("empty inputs are a no-op, not an exception")
    void emptyInputsAreNoOp() {
        service.cacheExaHighlights(List.of(), List.of(), "topic");
        service.cacheExaHighlights(null, null, "topic");

        verify(repository, never()).save(any());
    }

    @Test
    @DisplayName("topicKey slugifies, lowercases, and caps length, same shape as RoadmapService's own slugify")
    void topicKeySlugifies() {
        assertThat(ResourceEnrichmentService.topicKey("Ownership & Borrowing!")).isEqualTo("ownership-borrowing");
        assertThat(ResourceEnrichmentService.topicKey("  leading/trailing spaces  ")).isEqualTo("leading-trailing-spaces");
        assertThat(ResourceEnrichmentService.topicKey(null)).isEmpty();
        assertThat(ResourceEnrichmentService.topicKey("x".repeat(200))).hasSize(128);
    }

    // ── enrich() / RES-3 fallback fetch+summarize ────────────────────────────────────────

    @Test
    @DisplayName("a cache hit returns the cached enrichment without fetching or calling the AI")
    void enrichReturnsCachedResultWithoutRefetching() {
        ResourceEnrichment cached = new ResourceEnrichment();
        cached.setKind("written");
        cached.setFocusPointer("Cached pointer.");
        cached.setSource("exa_highlight");
        when(repository.findByResourceUrlAndTopicKey("https://example.com/a", "topic"))
                .thenReturn(Optional.of(cached));

        EnrichmentResponse result = service.enrich("https://example.com/a", "topic");

        assertThat(result.focusPointer()).isEqualTo("Cached pointer.");
        assertThat(result.source()).isEqualTo("exa_highlight");
        verifyNoInteractions(pageFetcher, resourceAi);
    }

    @Test
    @DisplayName("a cache miss fetches the page, asks the AI, and caches the result as fetch_fallback")
    void enrichCacheMissFetchesAndCaches() {
        when(repository.findByResourceUrlAndTopicKey(any(), any())).thenReturn(Optional.empty());
        when(pageFetcher.fetchText("https://example.com/a")).thenReturn("The real page text.");
        when(resourceAi.focusPointer("Ownership", "The real page text.")).thenReturn("Focus on X.");

        EnrichmentResponse result = service.enrich("https://example.com/a", "Ownership");

        assertThat(result.focusPointer()).isEqualTo("Focus on X.");
        assertThat(result.source()).isEqualTo("fetch_fallback");
        assertThat(result.kind()).isEqualTo("written");

        ArgumentCaptor<ResourceEnrichment> captor = ArgumentCaptor.forClass(ResourceEnrichment.class);
        verify(repository).save(captor.capture());
        assertThat(captor.getValue().getTopicKey()).isEqualTo("ownership");
    }

    @Test
    @DisplayName("a failed fetch degrades to no enrichment, caches nothing, and logs a brief system event")
    void enrichDegradesOnFetchFailure() {
        when(repository.findByResourceUrlAndTopicKey(any(), any())).thenReturn(Optional.empty());
        when(pageFetcher.fetchText(anyString())).thenReturn(null);

        EnrichmentResponse result = service.enrich("https://example.com/a", "topic");

        assertThat(result).isNull();
        verify(repository, never()).save(any());
        verifyNoInteractions(resourceAi);
        verify(events).systemError(eq("resource_fetch_failed"), anyString(), any());
    }

    @Test
    @DisplayName("an AI call that couldn't produce a pointer also degrades to no enrichment, not a fabricated one")
    void enrichDegradesWhenAiHasNothingUseful() {
        when(repository.findByResourceUrlAndTopicKey(any(), any())).thenReturn(Optional.empty());
        when(pageFetcher.fetchText(anyString())).thenReturn("Page text unrelated to the topic.");
        when(resourceAi.focusPointer(any(), any())).thenReturn(null);

        EnrichmentResponse result = service.enrich("https://example.com/a", "topic");

        assertThat(result).isNull();
        verify(repository, never()).save(any());
    }

    @Test
    @DisplayName("a blank resource url is a no-op")
    void enrichBlankUrlIsNoOp() {
        assertThat(service.enrich("", "topic")).isNull();
        assertThat(service.enrich(null, "topic")).isNull();
        verifyNoInteractions(pageFetcher, resourceAi, repository);
    }
}
