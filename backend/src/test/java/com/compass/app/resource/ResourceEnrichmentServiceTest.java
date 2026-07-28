package com.compass.app.resource;

import com.compass.app.ai.ResourceAiService;
import com.compass.app.ai.SearchGroundingService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * RES-2: caching a real Exa highlight as a resource's focus pointer costs no extra AI call and
 * no extra fetch — it's just surfacing something already computed. Pins down the actual
 * matching/threshold/dedup rules, since those are exactly the kind of thing that silently does
 * nothing if a condition is subtly wrong (matches V3-2.7's own lesson about this codebase).
 */
class ResourceEnrichmentServiceTest {

    private ResourceEnrichmentRepository repository;
    private ResourceEnrichmentService service;

    @BeforeEach
    void setUp() {
        repository = mock(ResourceEnrichmentRepository.class);
        service = new ResourceEnrichmentService(repository);
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
}
