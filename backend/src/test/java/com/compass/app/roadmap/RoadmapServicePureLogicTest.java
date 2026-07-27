package com.compass.app.roadmap;

import com.compass.app.ai.AiVoiceService;
import com.compass.app.ai.EmbeddingService;
import com.compass.app.ai.ResourceAiService;
import com.compass.app.ai.ReviewAiService;
import com.compass.app.ai.RoadmapAiService;
import com.compass.app.ai.SearchGroundingService;
import com.compass.app.entry.Entry;
import com.compass.app.entry.EntryRepository;
import com.compass.app.entry.EntryService;
import com.compass.app.events.EventService;
import com.compass.app.profile.ProfileService;
import com.compass.app.resource.ResourceService;
import com.compass.app.topic.CanonicalTopicRepository;
import com.compass.app.topic.TopicMatcherService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.Mockito;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The pure, deterministic corners of {@link RoadmapService} — tier/shape reconciliation, the
 * duplicate-module title heuristic, and the step-depth cap — none of which need an AI call, a
 * database, or a Spring context. {@link EntryRepository} is stubbed only where {@link
 * RoadmapService#depthOf} genuinely needs it.
 */
class RoadmapServicePureLogicTest {

    private EntryRepository repository;
    private RoadmapService service;

    @BeforeEach
    void setUp() {
        repository = mock(EntryRepository.class);
        service = new RoadmapService(
                repository,
                mock(RoadmapAiService.class),
                mock(ProfileService.class),
                mock(SearchGroundingService.class),
                mock(ResourceService.class),
                mock(EntryService.class),
                mock(AiVoiceService.class),
                mock(EventService.class),
                mock(TopicMatcherService.class),
                mock(CanonicalTopicRepository.class),
                mock(EmbeddingService.class),
                mock(ReviewAiService.class),
                Executors.newSingleThreadExecutor(),
                5);
    }

    // ── reconcileShapeWithTier ──────────────────────────────────────────────────────

    @Test
    @DisplayName("a null tier leaves the independently-assessed shape untouched")
    void nullTierKeepsAssessedShape() {
        RoadmapAiService.GoalAssessment assessment = assessment("nested");

        RoadmapAiService.GoalAssessment result = RoadmapService.reconcileShapeWithTier(assessment, null);

        assertThat(result).isSameAs(assessment);
    }

    @Test
    @DisplayName("MINI derives a flat shape, overriding a nested assessment")
    void miniTierDerivesFlatShape() {
        RoadmapAiService.GoalAssessment assessment = assessment("nested");

        RoadmapAiService.GoalAssessment result = RoadmapService.reconcileShapeWithTier(assessment, "MINI");

        assertThat(result.shape()).isEqualTo("flat");
        // Everything else about the assessment survives the reconciliation unchanged.
        assertThat(result.domain()).isEqualTo(assessment.domain());
        assertThat(result.complexity()).isEqualTo(assessment.complexity());
    }

    @ParameterizedTest
    @CsvSource({"TOPIC", "CAREER"})
    @DisplayName("TOPIC and CAREER derive a nested shape, overriding a flat assessment")
    void topicAndCareerDeriveNestedShape(String tier) {
        RoadmapAiService.GoalAssessment assessment = assessment("flat");

        RoadmapAiService.GoalAssessment result = RoadmapService.reconcileShapeWithTier(assessment, tier);

        assertThat(result.shape()).isEqualTo("nested");
    }

    @Test
    @DisplayName("an already-agreeing shape returns the same instance rather than a rebuilt copy")
    void agreeingShapeReturnsSameInstance() {
        RoadmapAiService.GoalAssessment assessment = assessment("flat");

        RoadmapAiService.GoalAssessment result = RoadmapService.reconcileShapeWithTier(assessment, "MINI");

        assertThat(result).isSameAs(assessment);
    }

    @Test
    @DisplayName("an unrecognised tier string derives nothing and keeps the assessed shape")
    void unrecognisedTierKeepsAssessedShape() {
        RoadmapAiService.GoalAssessment assessment = assessment("nested");

        RoadmapAiService.GoalAssessment result = RoadmapService.reconcileShapeWithTier(assessment, "TASK");

        assertThat(result).isSameAs(assessment);
    }

    private static RoadmapAiService.GoalAssessment assessment(String shape) {
        return new RoadmapAiService.GoalAssessment(3, 20, "engineering", "some_experience", shape, null);
    }

    // ── similarTitle ─────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("exact match (case/whitespace insensitive) is similar")
    void exactMatchIsSimilar() {
        assertThat(RoadmapService.similarTitle("  Ownership & Borrowing  ", "ownership & borrowing")).isTrue();
    }

    @Test
    @DisplayName("one title containing the other is flagged similar")
    void containmentIsSimilar() {
        assertThat(RoadmapService.similarTitle("Ownership", "Ownership & Borrowing")).isTrue();
        assertThat(RoadmapService.similarTitle("Ownership & Borrowing", "Ownership")).isTrue();
    }

    @Test
    @DisplayName("unrelated titles are not similar")
    void unrelatedTitlesAreNotSimilar() {
        assertThat(RoadmapService.similarTitle("Ownership & Borrowing", "Async Rust")).isFalse();
    }

    @Test
    @DisplayName("a null title never matches — flag only, never a false positive from missing data")
    void nullTitleIsNeverSimilar() {
        assertThat(RoadmapService.similarTitle(null, "Ownership")).isFalse();
        assertThat(RoadmapService.similarTitle("Ownership", null)).isFalse();
        assertThat(RoadmapService.similarTitle(null, null)).isFalse();
    }

    // ── depthOf / MAX_STEP_DEPTH gating ─────────────────────────────────────────────

    @Test
    @DisplayName("the root roadmap entry (no parent) is depth 0, no repository call needed")
    void rootHasDepthZero() {
        Entry root = new Entry();
        root.setParentId(null);

        assertThat(service.depthOf(root)).isZero();
        Mockito.verifyNoInteractions(repository);
    }

    @Test
    @DisplayName("depth is the size of the ancestor chain the repository reports")
    void depthComesFromAncestorCount() {
        Entry step = new Entry();
        step.setParentId(42L);
        when(repository.findAncestors(Mockito.any())).thenReturn(List.of(new Entry(), new Entry()));

        assertThat(service.depthOf(step)).isEqualTo(2);
    }

    @Test
    @DisplayName("a step below the cap is not at max depth")
    void belowCapIsNotAtMax() {
        Entry step = new Entry();
        step.setParentId(1L);
        when(repository.findById(99L)).thenReturn(java.util.Optional.of(step));
        when(repository.findAncestors(Mockito.any())).thenReturn(List.of(new Entry()));

        assertThat(service.isAtMaxStepDepth(99L)).isFalse();
    }

    @Test
    @DisplayName("a step exactly at MAX_STEP_DEPTH is at max, not one past it")
    void exactlyAtCapIsAtMax() {
        Entry step = new Entry();
        step.setParentId(1L);
        when(repository.findById(99L)).thenReturn(java.util.Optional.of(step));
        when(repository.findAncestors(Mockito.any()))
                .thenReturn(Collections.nCopies(RoadmapService.MAX_STEP_DEPTH, new Entry()));

        assertThat(service.isAtMaxStepDepth(99L)).isTrue();
    }

    @Test
    @DisplayName("a missing entry is not treated as being at the cap")
    void missingEntryIsNotAtMax() {
        when(repository.findById(404L)).thenReturn(java.util.Optional.empty());

        assertThat(service.isAtMaxStepDepth(404L)).isFalse();
    }
}
