package com.compass.app.topic;

import com.compass.app.ai.EmbeddingService;
import com.compass.app.ai.TopicAiService;
import com.compass.app.events.EventService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Cosine similarity itself, with fixed vectors, plus the RB-3.3 threshold banding that decides
 * whether the AI judgment call gets skipped. No embedding call, no AI call.
 */
class TopicMatcherServiceTest {

    // ── cosineSimilarity ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("identical vectors have similarity 1.0")
    void identicalVectorsAreFullySimilar() {
        List<Double> v = List.of(1.0, 2.0, 3.0);
        assertThat(TopicMatcherService.cosineSimilarity(v, v)).isEqualTo(1.0, offset());
    }

    @Test
    @DisplayName("orthogonal vectors have similarity 0.0")
    void orthogonalVectorsAreDissimilar() {
        assertThat(TopicMatcherService.cosineSimilarity(List.of(1.0, 0.0), List.of(0.0, 1.0)))
                .isEqualTo(0.0, offset());
    }

    @Test
    @DisplayName("opposite vectors have similarity -1.0")
    void oppositeVectorsAreNegativelySimilar() {
        assertThat(TopicMatcherService.cosineSimilarity(List.of(1.0, 0.0), List.of(-1.0, 0.0)))
                .isEqualTo(-1.0, offset());
    }

    @Test
    @DisplayName("mismatched lengths are treated as dissimilar rather than throwing")
    void mismatchedLengthsReturnZero() {
        assertThat(TopicMatcherService.cosineSimilarity(List.of(1.0, 2.0), List.of(1.0))).isZero();
    }

    @Test
    @DisplayName("null vectors are treated as dissimilar rather than throwing an NPE")
    void nullVectorsReturnZero() {
        assertThat(TopicMatcherService.cosineSimilarity(null, List.of(1.0))).isZero();
        assertThat(TopicMatcherService.cosineSimilarity(List.of(1.0), null)).isZero();
    }

    @Test
    @DisplayName("a zero vector returns zero rather than dividing by zero into NaN")
    void zeroVectorReturnsZeroNotNaN() {
        double result = TopicMatcherService.cosineSimilarity(List.of(0.0, 0.0), List.of(1.0, 1.0));
        assertThat(result).isZero();
        assertThat(Double.isNaN(result)).isFalse();
    }

    private static org.assertj.core.data.Offset<Double> offset() {
        return org.assertj.core.data.Offset.offset(1e-9);
    }

    // ── threshold banding in match() ───────────────────────────────────────────────

    private CanonicalTopicRepository repository;
    private EmbeddingService embeddings;
    private TopicAiService topicAi;
    private EventService events;
    private TopicMatcherService service;

    @BeforeEach
    void setUp() {
        repository = mock(CanonicalTopicRepository.class);
        embeddings = mock(EmbeddingService.class);
        topicAi = mock(TopicAiService.class);
        events = mock(EventService.class);
        service = new TopicMatcherService(repository, embeddings, topicAi, events);
    }

    @Test
    @DisplayName("null embedding (unavailable) short-circuits to no match, no repository call")
    void nullEmbeddingShortCircuits() {
        when(embeddings.embed(any())).thenReturn(null);

        assertThat(service.match("learn rust")).isNull();
        verify(repository, never()).findAll();
    }

    @Test
    @DisplayName("no stored topics short-circuits to no match")
    void noStoredTopicsShortCircuits() {
        when(embeddings.embed(any())).thenReturn(List.of(1.0, 0.0));
        when(repository.findAll()).thenReturn(List.of());

        assertThat(service.match("learn rust")).isNull();
    }

    @Test
    @DisplayName("a clearly high raw similarity skips the AI judgment call entirely")
    void highSimilaritySkipsAiJudgment() {
        CanonicalTopic topic = topicWithEmbedding(List.of(1.0, 0.0));
        when(embeddings.embed(any())).thenReturn(List.of(1.0, 0.0));
        when(repository.findAll()).thenReturn(List.of(topic));

        TopicMatcherService.MatchResult result = service.match("learn rust systems programming");

        assertThat(result.matchType()).isEqualTo("exact");
        assertThat(result.rawSimilarity()).isGreaterThanOrEqualTo(0.85);
        verify(topicAi, never()).classifyMatch(any(), any(), anyList(), anyList());
    }

    @Test
    @DisplayName("a clearly low raw similarity skips the AI judgment call and reports 'new'")
    void lowSimilaritySkipsAiJudgmentAndIsNew() {
        CanonicalTopic topic = topicWithEmbedding(List.of(0.0, 1.0));
        when(embeddings.embed(any())).thenReturn(List.of(1.0, 0.0));
        when(repository.findAll()).thenReturn(List.of(topic));

        TopicMatcherService.MatchResult result = service.match("bake sourdough bread");

        assertThat(result.matchType()).isEqualTo("new");
        verify(topicAi, never()).classifyMatch(any(), any(), anyList(), anyList());
    }

    @Test
    @DisplayName("an ambiguous raw similarity defers to the AI judgment call")
    void ambiguousSimilarityAsksAi() {
        // cos(45 degrees) ~= 0.707, squarely between LOW_SIMILARITY (0.35) and HIGH_SIMILARITY (0.85).
        when(embeddings.embed(any())).thenReturn(List.of(1.0, 1.0));
        CanonicalTopic topic = topicWithEmbedding(List.of(1.0, 0.0));
        when(repository.findAll()).thenReturn(List.of(topic));
        when(topicAi.classifyMatch(any(), any(), anyList(), anyList()))
                .thenReturn(new TopicAiService.MatchJudgment("subtopic", 0.6, "close enough"));

        TopicMatcherService.MatchResult result = service.match("learn rust web frameworks");

        assertThat(result.matchType()).isEqualTo("subtopic");
        verify(topicAi).classifyMatch(any(), any(), anyList(), anyList());
    }

    @Test
    @DisplayName("an ambiguous similarity whose AI judgment call fails degrades to 'new', not an exception")
    void ambiguousSimilarityWithFailedAiJudgmentDegradesToNew() {
        CanonicalTopic topic = topicWithEmbedding(List.of(1.0, 0.0));
        when(embeddings.embed(any())).thenReturn(List.of(1.0, 1.0));
        when(repository.findAll()).thenReturn(List.of(topic));
        when(topicAi.classifyMatch(any(), any(), anyList(), anyList())).thenReturn(null);

        TopicMatcherService.MatchResult result = service.match("learn rust web frameworks");

        assertThat(result.matchType()).isEqualTo("new");
        assertThat(result.confidence()).isZero();
    }

    // A mock rather than a real entity: CanonicalTopic.id is JPA-generated with no setter, and
    // match() reads it unconditionally for the system_events context — a real never-persisted
    // instance would NPE there for reasons that have nothing to do with what's under test here.
    private static CanonicalTopic topicWithEmbedding(List<Double> embedding) {
        CanonicalTopic topic = mock(CanonicalTopic.class);
        when(topic.getId()).thenReturn(1L);
        when(topic.getCanonicalName()).thenReturn("Rust Systems Programming");
        when(topic.getEmbedding()).thenReturn(embedding);
        when(topic.getUsageCount()).thenReturn(1);
        return topic;
    }
}
