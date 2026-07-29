package com.compass.app.roadmap;

import com.compass.app.ai.AiVoiceService;
import com.compass.app.ai.ResourceAiService;
import com.compass.app.ai.RoadmapAiService;
import com.compass.app.ai.SearchGroundingService;
import com.compass.app.ai.Tier;
import com.compass.app.entry.Entry;
import com.compass.app.entry.EntryRepository;
import com.compass.app.entry.EntryService;
import com.compass.app.entry.EntryStatus;
import com.compass.app.entry.EntryType;
import com.compass.app.events.EventService;
import com.compass.app.profile.ProfileService;
import com.compass.app.resource.ResourceService;
import com.compass.app.roadmap.dto.GenerateRoadmapRequest;
import com.compass.app.roadmap.dto.GenerateRoadmapResponse;
import com.compass.app.topic.TopicMatcherService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Direct, focused coverage of {@link RoadmapGenerationService} (V3-4.1's fourth and final
 * extracted seam from the former {@code RoadmapService} god-file — see {@code TASKS_v3.md}),
 * retargeting the same generation/expansion assertions {@link RoadmapServiceBehaviorTest} used to
 * make through {@code RoadmapService}'s facade, back when it still held this logic directly. Once
 * this seam moved, the facade became pure delegation with nothing left to unit-test on its own —
 * see {@code RoadmapService}'s own javadoc — so {@code RoadmapServiceBehaviorTest} and {@code
 * RoadmapServicePureLogicTest} were retired in the same commit as this file's addition, their
 * coverage fully redistributed across this file and the three sibling {@code Roadmap*ServiceTest}
 * files from the earlier seams.
 */
class RoadmapGenerationServiceTest {

    private EntryRepository repository;
    private RoadmapQueryService queryService;
    private RoadmapStructureService structureService;
    private RoadmapAiService roadmapAi;
    private ProfileService profileService;
    private SearchGroundingService searchGrounding;
    private ResourceService resourceService;
    private EntryService entryService;
    private AiVoiceService aiVoice;
    private EventService events;
    private TopicMatcherService topicMatcher;
    private RoadmapGenerationService service;

    @BeforeEach
    void setUp() {
        repository = mock(EntryRepository.class);
        queryService = new RoadmapQueryService(repository);
        structureService = mock(RoadmapStructureService.class);
        roadmapAi = mock(RoadmapAiService.class);
        profileService = mock(ProfileService.class);
        searchGrounding = mock(SearchGroundingService.class);
        resourceService = mock(ResourceService.class);
        entryService = mock(EntryService.class);
        aiVoice = mock(AiVoiceService.class);
        events = mock(EventService.class);
        topicMatcher = mock(TopicMatcherService.class);
        service = new RoadmapGenerationService(repository, queryService, structureService, roadmapAi,
                profileService, searchGrounding, resourceService, entryService, aiVoice, events,
                topicMatcher, Executors.newSingleThreadExecutor(), 5);

        when(profileService.confirmedProfile()).thenReturn(Optional.empty());
    }

    private static long nextId = 1000;

    private static void setId(Entry e, long id) {
        try {
            java.lang.reflect.Field idField = Entry.class.getDeclaredField("id");
            idField.setAccessible(true);
            idField.set(e, id);
        } catch (ReflectiveOperationException ex) {
            throw new RuntimeException(ex);
        }
    }

    private static Entry entryWithId(long id, EntryType type, EntryStatus status, Long parentId,
                                     Integer orderIndex, Map<String, Object> content) {
        Entry e = new Entry();
        setId(e, id);
        e.setType(type);
        e.setStatus(status);
        e.setParentId(parentId);
        e.setOrderIndex(orderIndex);
        e.setContent(content == null ? new HashMap<>() : content);
        return e;
    }

    private static Entry entryWithId(long id, EntryType type, EntryStatus status, Long parentId) {
        return entryWithId(id, type, status, parentId, null, null);
    }

    private static Entry roadmap(long id, String title) {
        Map<String, Object> content = new HashMap<>();
        content.put("title", title);
        return entryWithId(id, EntryType.ROADMAP, EntryStatus.IN_MOTION, null, null, content);
    }

    private static Entry module(long id, long roadmapId, String title, Integer orderIndex) {
        Map<String, Object> content = new HashMap<>();
        content.put("title", title);
        return entryWithId(id, EntryType.ROADMAP, EntryStatus.IN_MOTION, roadmapId, orderIndex, content);
    }

    private static Entry step(long id, long parentId, String text, EntryStatus status, Integer orderIndex) {
        Map<String, Object> content = new HashMap<>();
        content.put("text", text);
        return entryWithId(id, EntryType.ROADMAP_STEP, status, parentId, orderIndex, content);
    }

    // ── reconcileShapeWithTier ──────────────────────────────────────────────────────

    @Test
    @DisplayName("a null tier leaves the independently-assessed shape untouched")
    void nullTierKeepsAssessedShape() {
        RoadmapAiService.GoalAssessment assessment = assessment("nested");

        RoadmapAiService.GoalAssessment result = RoadmapGenerationService.reconcileShapeWithTier(assessment, null);

        assertThat(result).isSameAs(assessment);
    }

    @Test
    @DisplayName("MINI derives a flat shape, overriding a nested assessment")
    void miniTierDerivesFlatShape() {
        RoadmapAiService.GoalAssessment assessment = assessment("nested");

        RoadmapAiService.GoalAssessment result = RoadmapGenerationService.reconcileShapeWithTier(assessment, "MINI");

        assertThat(result.shape()).isEqualTo("flat");
        assertThat(result.domain()).isEqualTo(assessment.domain());
        assertThat(result.complexity()).isEqualTo(assessment.complexity());
    }

    @ParameterizedTest
    @CsvSource({"TOPIC", "CAREER"})
    @DisplayName("TOPIC and CAREER derive a nested shape, overriding a flat assessment")
    void topicAndCareerDeriveNestedShape(String tier) {
        RoadmapAiService.GoalAssessment assessment = assessment("flat");

        RoadmapAiService.GoalAssessment result = RoadmapGenerationService.reconcileShapeWithTier(assessment, tier);

        assertThat(result.shape()).isEqualTo("nested");
    }

    @Test
    @DisplayName("an already-agreeing shape returns the same instance rather than a rebuilt copy")
    void agreeingShapeReturnsSameInstance() {
        RoadmapAiService.GoalAssessment assessment = assessment("flat");

        RoadmapAiService.GoalAssessment result = RoadmapGenerationService.reconcileShapeWithTier(assessment, "MINI");

        assertThat(result).isSameAs(assessment);
    }

    @Test
    @DisplayName("an unrecognised tier string derives nothing and keeps the assessed shape")
    void unrecognisedTierKeepsAssessedShape() {
        RoadmapAiService.GoalAssessment assessment = assessment("nested");

        RoadmapAiService.GoalAssessment result = RoadmapGenerationService.reconcileShapeWithTier(assessment, "TASK");

        assertThat(result).isSameAs(assessment);
    }

    private static RoadmapAiService.GoalAssessment assessment(String shape) {
        return new RoadmapAiService.GoalAssessment(3, 20, "engineering", "some_experience", shape, null);
    }

    // ── similarTitle ─────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("exact match (case/whitespace insensitive) is similar")
    void exactMatchIsSimilar() {
        assertThat(RoadmapGenerationService.similarTitle("  Ownership & Borrowing  ", "ownership & borrowing")).isTrue();
    }

    @Test
    @DisplayName("one title containing the other is flagged similar")
    void containmentIsSimilar() {
        assertThat(RoadmapGenerationService.similarTitle("Ownership", "Ownership & Borrowing")).isTrue();
    }

    @Test
    @DisplayName("unrelated titles are not similar")
    void unrelatedTitlesAreNotSimilar() {
        assertThat(RoadmapGenerationService.similarTitle("Ownership & Borrowing", "Async Rust")).isFalse();
    }

    @Test
    @DisplayName("a null title never matches — flag only, never a false positive from missing data")
    void nullTitleIsNeverSimilar() {
        assertThat(RoadmapGenerationService.similarTitle(null, "Ownership")).isFalse();
        assertThat(RoadmapGenerationService.similarTitle("Ownership", null)).isFalse();
    }

    // ── generate(): routing branches ───────────────────────────────────────────────────

    @Test
    @DisplayName("an empty goal is rejected before any AI call")
    void generateRejectsEmptyGoal() {
        GenerateRoadmapRequest req = new GenerateRoadmapRequest("  ", null, false, null, false);

        assertThatThrownBy(() -> service.generate(req)).isInstanceOf(IllegalArgumentException.class);
        verify(roadmapAi, never()).isAvailable();
    }

    @Test
    @DisplayName("generate throws when no AI provider is available")
    void generateThrowsWhenAiUnavailable() {
        when(roadmapAi.isAvailable()).thenReturn(false);
        GenerateRoadmapRequest req = new GenerateRoadmapRequest("Learn Rust", null, false, null, false);

        assertThatThrownBy(() -> service.generate(req)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("a confident TASK classification routes straight to a task entry, no clarifying questions")
    void generateRoutesConfidentTaskToTaskEntry() {
        when(roadmapAi.isAvailable()).thenReturn(true);
        when(roadmapAi.classifyTier("Buy milk", null))
                .thenReturn(new RoadmapAiService.TierClassification(Tier.TASK, 0.9, "trivial"));
        Entry task = entryWithId(5, EntryType.TASK, EntryStatus.CAPTURED, null);
        when(entryService.create(any())).thenReturn(task);
        when(aiVoice.acknowledge(task)).thenReturn("Held.");
        GenerateRoadmapRequest req = new GenerateRoadmapRequest("Buy milk", null, false, null, false);

        GenerateRoadmapResponse resp = service.generate(req);

        assertThat(resp.status()).isEqualTo("routed_to_task");
        assertThat(resp.routedTask().entryId()).isEqualTo(5L);
        verify(roadmapAi, never()).clarifyingQuestions(any(), any());
    }

    @Test
    @DisplayName("a low-confidence TASK classification falls through to the normal pipeline instead of routing")
    void generateLowConfidenceTaskFallsThroughToPipeline() {
        when(roadmapAi.isAvailable()).thenReturn(true);
        when(roadmapAi.classifyTier(any(), any()))
                .thenReturn(new RoadmapAiService.TierClassification(Tier.TASK, 0.5, "unsure"));
        when(topicMatcher.match(any())).thenReturn(null);
        when(roadmapAi.clarifyingQuestions(any(), any())).thenReturn(List.of("What exactly?"));
        GenerateRoadmapRequest req = new GenerateRoadmapRequest("Ambiguous goal", null, false, null, false);

        GenerateRoadmapResponse resp = service.generate(req);

        assertThat(resp.status()).isEqualTo("needs_clarification");
        verify(entryService, never()).create(any());
    }

    @Test
    @DisplayName("a topic match (not 'new') short-circuits generation with a topic_match response")
    void generateReturnsTopicMatchWhenFound() {
        when(roadmapAi.isAvailable()).thenReturn(true);
        when(roadmapAi.classifyTier(any(), any())).thenReturn(null); // classification unavailable, still continues
        com.compass.app.topic.CanonicalTopic topic = new com.compass.app.topic.CanonicalTopic();
        topic.setCanonicalName("Existing Topic");
        topic.setRoadmapEntryId(77L);
        when(topicMatcher.match(any())).thenReturn(new TopicMatcherService.MatchResult(
                "exact", 0.95, "same thing", topic, 0.9));
        GenerateRoadmapRequest req = new GenerateRoadmapRequest("Existing Topic", null, false, null, false);

        GenerateRoadmapResponse resp = service.generate(req);

        assertThat(resp.status()).isEqualTo("topic_match");
        assertThat(resp.topicMatch().matchType()).isEqualTo("exact");
        verify(roadmapAi, never()).clarifyingQuestions(any(), any());
    }

    @Test
    @DisplayName("skipTopicMatch bypasses the topic-match check entirely")
    void generateSkipsTopicMatchWhenRequested() {
        when(roadmapAi.isAvailable()).thenReturn(true);
        when(roadmapAi.classifyTier(any(), any())).thenReturn(null);
        when(roadmapAi.clarifyingQuestions(any(), any())).thenReturn(List.of("Q?"));
        GenerateRoadmapRequest req = new GenerateRoadmapRequest("Goal", null, false, null, true);

        service.generate(req);

        verify(topicMatcher, never()).match(any());
    }

    @Test
    @DisplayName("zero clarifying questions drafts immediately instead of showing an empty question form")
    void generateDraftsImmediatelyWhenNoQuestions() {
        when(roadmapAi.isAvailable()).thenReturn(true);
        when(roadmapAi.classifyTier(any(), any())).thenReturn(null);
        when(topicMatcher.match(any())).thenReturn(null);
        when(roadmapAi.clarifyingQuestions(any(), any())).thenReturn(List.of());
        when(searchGrounding.ground(any())).thenReturn(null);
        when(roadmapAi.assessGoal(any(), any(), any(), any())).thenReturn(
                new RoadmapAiService.GoalAssessment(2, 5, "domain", "none", "flat", "quick_task"));
        when(roadmapAi.proposeFlat(any(), any(), any(), any(), any(), any())).thenReturn(
                new RoadmapAiService.FlatProposal("Title", null, List.of(
                        new RoadmapAiService.DraftStep("step 1", "concept", "small", null, null, null)),
                        List.of()));
        GenerateRoadmapRequest req = new GenerateRoadmapRequest("Goal", null, false, null, false);

        GenerateRoadmapResponse resp = service.generate(req);

        assertThat(resp.status()).isEqualTo("proposal");
        verify(roadmapAi, never()).moduleOutline(any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("clarifyingQuestions returning null (AI unavailable mid-call) throws")
    void generateThrowsWhenClarifyingQuestionsFail() {
        when(roadmapAi.isAvailable()).thenReturn(true);
        when(roadmapAi.classifyTier(any(), any())).thenReturn(null);
        when(topicMatcher.match(any())).thenReturn(null);
        when(roadmapAi.clarifyingQuestions(any(), any())).thenReturn(null);
        GenerateRoadmapRequest req = new GenerateRoadmapRequest("Goal", null, false, null, false);

        assertThatThrownBy(() -> service.generate(req)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("with clarifications answered and a genuine follow-up, another needs_clarification is returned")
    void generateReturnsGenuineFollowUp() {
        when(roadmapAi.isAvailable()).thenReturn(true);
        when(roadmapAi.followUpQuestions(any(), any(), any())).thenReturn(List.of("One more thing?"));
        GenerateRoadmapRequest req = new GenerateRoadmapRequest("Goal",
                List.of(new GenerateRoadmapRequest.Clarification("Q1", "A1")), false, null, false);

        GenerateRoadmapResponse resp = service.generate(req);

        assertThat(resp.status()).isEqualTo("needs_clarification");
        assertThat(resp.questions()).containsExactly("One more thing?");
    }

    @Test
    @DisplayName("skipFollowUp true drafts directly without checking for a follow-up round at all")
    void generateSkipsFollowUpCheckWhenRequested() {
        when(roadmapAi.isAvailable()).thenReturn(true);
        when(searchGrounding.ground(any())).thenReturn(null);
        when(roadmapAi.assessGoal(any(), any(), any(), any())).thenReturn(
                new RoadmapAiService.GoalAssessment(2, 5, "domain", "none", "flat", "quick_task"));
        when(roadmapAi.proposeFlat(any(), any(), any(), any(), any(), any())).thenReturn(
                new RoadmapAiService.FlatProposal("Title", null, List.of(), List.of()));
        GenerateRoadmapRequest req = new GenerateRoadmapRequest("Goal",
                List.of(new GenerateRoadmapRequest.Clarification("Q1", "A1")), true, null, false);

        service.generate(req);

        verify(roadmapAi, never()).followUpQuestions(any(), any(), any());
    }

    @Test
    @DisplayName("a failed goal assessment falls back to a default mid-range/nested assessment rather than blocking")
    void generateFallsBackOnFailedAssessment() {
        when(roadmapAi.isAvailable()).thenReturn(true);
        when(searchGrounding.ground(any())).thenReturn(null);
        when(roadmapAi.assessGoal(any(), any(), any(), any())).thenReturn(null);
        when(roadmapAi.moduleOutline(any(), any(), any(), any(), any(), any(), any())).thenReturn(
                new RoadmapAiService.RoadmapOutline("Title", null, List.of(), List.of()));
        GenerateRoadmapRequest req = new GenerateRoadmapRequest("Goal",
                List.of(new GenerateRoadmapRequest.Clarification("Q1", "A1")), true, null, false);

        GenerateRoadmapResponse resp = service.generate(req);

        assertThat(resp.status()).isEqualTo("outline"); // default shape is "nested"
    }

    @Test
    @DisplayName("a failed flat proposal throws rather than returning an unusable response")
    void generateThrowsWhenFlatProposalFails() {
        when(roadmapAi.isAvailable()).thenReturn(true);
        when(searchGrounding.ground(any())).thenReturn(null);
        when(roadmapAi.assessGoal(any(), any(), any(), any())).thenReturn(
                new RoadmapAiService.GoalAssessment(2, 5, "domain", "none", "flat", "quick_task"));
        when(roadmapAi.proposeFlat(any(), any(), any(), any(), any(), any())).thenReturn(null);
        GenerateRoadmapRequest req = new GenerateRoadmapRequest("Goal",
                List.of(new GenerateRoadmapRequest.Clarification("Q1", "A1")), true, null, false);

        assertThatThrownBy(() -> service.generate(req)).isInstanceOf(IllegalStateException.class);
    }

    // ── expandModule ────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("expandModule throws when the AI is unavailable")
    void expandModuleThrowsWhenAiUnavailable() {
        when(repository.findById(1L)).thenReturn(Optional.of(roadmap(1, "R")));
        when(repository.findById(2L)).thenReturn(Optional.of(module(2, 1, "M", 0)));
        when(roadmapAi.isAvailable()).thenReturn(false);

        assertThatThrownBy(() -> service.expandModule(1L, 2L)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("a successful full expand returns a proposal with real steps, no skeleton flag")
    void expandModuleFullSuccess() {
        Entry r = roadmap(1, "R");
        Entry m = module(2, 1, "M", 0);
        when(repository.findById(1L)).thenReturn(Optional.of(r));
        when(repository.findById(2L)).thenReturn(Optional.of(m));
        when(roadmapAi.isAvailable()).thenReturn(true);
        when(repository.findByParentIdOrderByOrderIndexAsc(anyLong())).thenReturn(List.of());
        when(searchGrounding.groundMulti(any())).thenReturn(null);
        when(roadmapAi.expandModule(any(), any(), any(), any(), any(), any(), any(), anyBoolean(), any()))
                .thenReturn(List.of(new RoadmapAiService.DraftStep("step 1", "concept", "small", null, null, null)));
        when(resourceService.suggestResourcesPerStep(any(), any(), any(), any())).thenReturn(List.of(List.of()));

        GenerateRoadmapResponse resp = service.expandModule(1L, 2L);

        assertThat(resp.status()).isEqualTo("proposal");
        assertThat(resp.skeletonOnly()).isFalse();
        assertThat(resp.steps()).hasSize(1);
    }

    @Test
    @DisplayName("when the full expand fails, a successful skeleton fallback returns titles-only steps flagged skeletonOnly")
    void expandModuleFallsBackToSkeleton() {
        Entry r = roadmap(1, "R");
        Entry m = module(2, 1, "M", 0);
        when(repository.findById(1L)).thenReturn(Optional.of(r));
        when(repository.findById(2L)).thenReturn(Optional.of(m));
        when(roadmapAi.isAvailable()).thenReturn(true);
        when(repository.findByParentIdOrderByOrderIndexAsc(anyLong())).thenReturn(List.of());
        when(searchGrounding.groundMulti(any())).thenReturn(null);
        when(roadmapAi.expandModule(any(), any(), any(), any(), any(), any(), any(), anyBoolean(), any()))
                .thenReturn(null); // whole heavy chain failed
        when(roadmapAi.skeletonModuleSteps(any(), any(), any())).thenReturn(List.of("Skeleton step"));

        GenerateRoadmapResponse resp = service.expandModule(1L, 2L);

        assertThat(resp.skeletonOnly()).isTrue();
        assertThat(resp.steps()).hasSize(1);
        assertThat(resp.steps().get(0).text()).isEqualTo("Skeleton step");
    }

    @Test
    @DisplayName("when both the full expand and the skeleton fallback fail, expandModule throws")
    void expandModuleThrowsWhenBothChainsFail() {
        Entry r = roadmap(1, "R");
        Entry m = module(2, 1, "M", 0);
        when(repository.findById(1L)).thenReturn(Optional.of(r));
        when(repository.findById(2L)).thenReturn(Optional.of(m));
        when(roadmapAi.isAvailable()).thenReturn(true);
        when(repository.findByParentIdOrderByOrderIndexAsc(anyLong())).thenReturn(List.of());
        when(searchGrounding.groundMulti(any())).thenReturn(null);
        when(roadmapAi.expandModule(any(), any(), any(), any(), any(), any(), any(), anyBoolean(), any()))
                .thenReturn(null);
        when(roadmapAi.skeletonModuleSteps(any(), any(), any())).thenReturn(null);

        assertThatThrownBy(() -> service.expandModule(1L, 2L)).isInstanceOf(IllegalStateException.class);
    }

    // ── retrySkeletonModule ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("retrySkeletonModule is a no-op (false) when the module has any real (non-skeleton) progress")
    void retrySkeletonModuleNoOpWithRealProgress() {
        Entry m = entryWithId(2, EntryType.ROADMAP, EntryStatus.IN_MOTION, 1L);
        when(repository.findById(2L)).thenReturn(Optional.of(m));
        when(repository.findById(1L)).thenReturn(Optional.of(roadmap(1, "R")));
        Entry realStep = step(3, 2, "worked on", EntryStatus.IN_MOTION, 0);
        when(repository.findByParentIdOrderByOrderIndexAsc(2L)).thenReturn(List.of(realStep));

        assertThat(service.retrySkeletonModule(2L)).isFalse();
        verify(roadmapAi, never()).expandModule(any(), any(), any(), any(), any(), any(), any(), anyBoolean(), any());
    }

    @Test
    @DisplayName("retrySkeletonModule replaces untouched skeleton steps with a successful full re-expand")
    void retrySkeletonModuleReplacesOnSuccess() {
        Map<String, Object> skeletonContent = new HashMap<>();
        skeletonContent.put("text", "skeleton");
        skeletonContent.put("skeletonOnly", true);
        Entry m = module(2, 1, "M", 0);
        when(repository.findById(2L)).thenReturn(Optional.of(m));
        Entry r = roadmap(1, "R");
        when(repository.findById(1L)).thenReturn(Optional.of(r));
        Entry skeletonStep = entryWithId(3, EntryType.ROADMAP_STEP, EntryStatus.CAPTURED, 2L, 0, skeletonContent);
        when(repository.findByParentIdOrderByOrderIndexAsc(2L)).thenReturn(List.of(skeletonStep));
        when(roadmapAi.isAvailable()).thenReturn(true);
        when(searchGrounding.groundMulti(any())).thenReturn(null);
        when(roadmapAi.expandModule(any(), any(), any(), any(), any(), any(), any(), anyBoolean(), any()))
                .thenReturn(List.of(new RoadmapAiService.DraftStep("full step", "concept", "small", null, null, null)));
        when(resourceService.suggestResourcesPerStep(any(), any(), any(), any())).thenReturn(List.of(List.of()));

        boolean result = service.retrySkeletonModule(2L);

        assertThat(result).isTrue();
        verify(repository).deleteAll(List.of(skeletonStep));
        verify(structureService).createDraftSteps(eq(2L), any());
    }

    // ── stepCovers ──────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("stepCovers returns the cached value without another AI call when already present")
    void stepCoversReturnsCachedValue() {
        Map<String, Object> content = new HashMap<>();
        content.put("text", "step text");
        content.put("covers", List.of("Cached bullet"));
        Entry stepEntry = entryWithId(2, EntryType.ROADMAP_STEP, EntryStatus.CAPTURED, 1L, 0, content);
        when(repository.findById(2L)).thenReturn(Optional.of(stepEntry));

        List<String> covers = service.stepCovers(2L);

        assertThat(covers).containsExactly("Cached bullet");
        verify(roadmapAi, never()).stepCovers(any(), any());
    }

    @Test
    @DisplayName("stepCovers generates and caches on first open, throws if the AI call fails")
    void stepCoversGeneratesAndCachesOnFirstOpen() {
        Map<String, Object> content = new HashMap<>();
        content.put("text", "step text");
        Entry stepEntry = entryWithId(2, EntryType.ROADMAP_STEP, EntryStatus.CAPTURED, 1L, 0, content);
        when(repository.findById(2L)).thenReturn(Optional.of(stepEntry));
        when(repository.findById(1L)).thenReturn(Optional.of(roadmap(1, "R")));
        when(roadmapAi.stepCovers("R", "step text")).thenReturn(List.of("Bullet 1", "Bullet 2"));

        List<String> covers = service.stepCovers(2L);

        assertThat(covers).containsExactly("Bullet 1", "Bullet 2");
        assertThat(stepEntry.getContent().get("covers")).isEqualTo(List.of("Bullet 1", "Bullet 2"));

        // A second, independent failure case: no cache, and the AI has nothing to say.
        Entry uncached = entryWithId(3, EntryType.ROADMAP_STEP, EntryStatus.CAPTURED, 1L, 0, new HashMap<>(Map.of("text", "x")));
        when(repository.findById(3L)).thenReturn(Optional.of(uncached));
        when(roadmapAi.stepCovers(any(), eq("x"))).thenReturn(null);
        assertThatThrownBy(() -> service.stepCovers(3L)).isInstanceOf(IllegalStateException.class);
    }
}
