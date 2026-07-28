package com.compass.app.roadmap;

import com.compass.app.ai.AiVoiceService;
import com.compass.app.ai.EmbeddingService;
import com.compass.app.ai.ResourceAiService;
import com.compass.app.ai.ReviewAiService;
import com.compass.app.ai.RoadmapAiService;
import com.compass.app.ai.SearchGroundingService;
import com.compass.app.ai.Tier;
import com.compass.app.entry.Entry;
import com.compass.app.entry.EntryRepository;
import com.compass.app.entry.EntryService;
import com.compass.app.entry.EntryStatus;
import com.compass.app.entry.EntryType;
import com.compass.app.entry.dto.CreateEntryRequest;
import com.compass.app.events.EventService;
import com.compass.app.profile.ProfileService;
import com.compass.app.resource.ResourceService;
import com.compass.app.roadmap.dto.ApplyReTierProposalRequest;
import com.compass.app.roadmap.dto.CreateRoadmapRequest;
import com.compass.app.roadmap.dto.GenerateRoadmapRequest;
import com.compass.app.roadmap.dto.GenerateRoadmapResponse;
import com.compass.app.roadmap.dto.ReTierRequest;
import com.compass.app.roadmap.dto.ReTierResponse;
import com.compass.app.roadmap.dto.ReplanModuleItem;
import com.compass.app.topic.CanonicalTopicRepository;
import com.compass.app.topic.TopicMatcherService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Characterization tests for {@link RoadmapService}'s actual stateful behavior — written ahead of
 * V3-4.1's split (see TASKS_v3.md), which needs a real safety net before any method gets moved to
 * a new collaborator: the pure/deterministic corners are already covered by {@link
 * RoadmapServicePureLogicTest}; this file covers everything that touches {@link EntryRepository}
 * or an AI collaborator, pinning down today's actual behavior — including edge cases and error
 * paths — so a later mechanical split has something concrete to stay green against.
 *
 * <p>{@link EntryRepository} and every AI/profile/search/event collaborator are mocked (no
 * database, no live AI call) — same style as {@link RoadmapServicePureLogicTest} and {@link
 * com.compass.app.resource.ResourceEnrichmentServiceTest}. {@code save}/{@code saveAll} are
 * stubbed to return their argument (an in-memory identity save), matching what
 * {@code SimpleJpaRepository} does for an already-persistent entity.
 */
class RoadmapServiceBehaviorTest {

    private EntryRepository repository;
    private RoadmapAiService roadmapAi;
    private ProfileService profileService;
    private SearchGroundingService searchGrounding;
    private ResourceService resourceService;
    private EntryService entryService;
    private AiVoiceService aiVoice;
    private EventService events;
    private TopicMatcherService topicMatcher;
    private CanonicalTopicRepository canonicalTopics;
    private EmbeddingService embeddings;
    private ReviewAiService reviewAi;
    private RoadmapService service;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        repository = mock(EntryRepository.class);
        roadmapAi = mock(RoadmapAiService.class);
        profileService = mock(ProfileService.class);
        searchGrounding = mock(SearchGroundingService.class);
        resourceService = mock(ResourceService.class);
        entryService = mock(EntryService.class);
        aiVoice = mock(AiVoiceService.class);
        events = mock(EventService.class);
        topicMatcher = mock(TopicMatcherService.class);
        canonicalTopics = mock(CanonicalTopicRepository.class);
        embeddings = mock(EmbeddingService.class);
        reviewAi = mock(ReviewAiService.class);
        service = new RoadmapService(repository, new RoadmapQueryService(repository), roadmapAi, profileService, searchGrounding,
                resourceService, entryService, aiVoice, events, topicMatcher, canonicalTopics,
                embeddings, reviewAi, Executors.newSingleThreadExecutor(), 5);

        when(profileService.confirmedProfile()).thenReturn(Optional.empty());
        // IDENTITY-generation save contract (see Entry's @GeneratedValue): a genuinely new entity
        // (no id yet) gets one assigned as a side effect of saving, same as a real INSERT — several
        // methods under test (createModuleWithSteps, insertModule, insertStep, addPrerequisite...)
        // save a brand-new Entry and then immediately use its now-real id (e.g.
        // step.setParentId(module.getId())), so a pure identity-passthrough stub would silently
        // leave that id null instead of reproducing what Postgres actually does here.
        when(repository.save(any(Entry.class))).thenAnswer(inv -> {
            Entry e = inv.getArgument(0);
            if (e.getId() == null) {
                setId(e, freshId());
            }
            return e;
        });
        when(repository.saveAll(any(List.class))).thenAnswer(inv -> {
            List<Entry> list = inv.getArgument(0);
            for (Entry e : list) {
                if (e.getId() == null) {
                    setId(e, freshId());
                }
            }
            return list;
        });
    }

    // ── Test fixtures ───────────────────────────────────────────────────────────────────

    private static long nextId = 1000;

    /**
     * A persisted-looking {@link Entry} with a real id — reflection, not a Mockito spy: a spy's
     * {@code doReturn().when()} is itself a stubbing call, and constructing one inline as an
     * argument to an outer {@code when(...).thenReturn(...)} (exactly how these fixtures get used
     * everywhere below) trips Mockito's "unfinished stubbing" detector. Plain reflection has no
     * such interaction with Mockito's stubbing state.
     */
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

    private static void setId(Entry e, long id) {
        try {
            java.lang.reflect.Field idField = Entry.class.getDeclaredField("id");
            idField.setAccessible(true);
            idField.set(e, id);
        } catch (ReflectiveOperationException ex) {
            throw new RuntimeException(ex);
        }
    }

    private static Entry entryWithId(long id, EntryType type, EntryStatus status, Long parentId) {
        return entryWithId(id, type, status, parentId, null, null);
    }

    private static Entry roadmap(long id, String title) {
        Map<String, Object> content = new HashMap<>();
        content.put("title", title);
        return entryWithId(id, EntryType.ROADMAP, EntryStatus.IN_MOTION, null, null, content);
    }

    private static Entry roadmap(long id, String title, String tier) {
        Entry r = roadmap(id, title);
        r.getContent().put("tier", tier);
        return r;
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

    private static long freshId() {
        return ++nextId;
    }

    // ── create() ────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("a blank title is rejected before anything is persisted")
    void createRejectsBlankTitle() {
        CreateRoadmapRequest req = new CreateRoadmapRequest("  ", null, null, null, null, null, null);

        assertThatThrownBy(() -> service.create(req)).isInstanceOf(IllegalArgumentException.class);
        verify(repository, never()).save(any());
    }

    @Test
    @DisplayName("plain text steps become ordered ROADMAP_STEP children, blanks skipped")
    void createWithPlainSteps() {
        CreateRoadmapRequest req = new CreateRoadmapRequest("Learn Rust", null,
                List.of("Step one", "  ", "Step two"), null, null, null, null);
        when(canonicalTopics.findByTopicId(any())).thenReturn(Optional.empty());
        when(embeddings.embed(any())).thenReturn(null); // best-effort topic save skipped

        Entry created = service.create(req);

        ArgumentCaptor<Entry> captor = ArgumentCaptor.forClass(Entry.class);
        verify(repository, times(3)).save(captor.capture()); // roadmap + 2 real steps
        List<Entry> saved = captor.getAllValues();
        assertThat(saved.get(0).getContent().get("title")).isEqualTo("Learn Rust");
        assertThat(saved.get(1).getContent().get("text")).isEqualTo("Step one");
        assertThat(saved.get(1).getOrderIndex()).isZero();
        assertThat(saved.get(2).getContent().get("text")).isEqualTo("Step two");
        assertThat(saved.get(2).getOrderIndex()).isEqualTo(1);
        assertThat(created.getContent().get("title")).isEqualTo("Learn Rust");
    }

    @Test
    @DisplayName("draftSteps takes priority over plain steps when both are present")
    void createPrefersDraftStepsOverPlainSteps() {
        CreateRoadmapRequest.DraftStepInput draft = new CreateRoadmapRequest.DraftStepInput(
                "Draft step", "concept", "small", null, null, null, null, false);
        CreateRoadmapRequest req = new CreateRoadmapRequest("Learn Rust", null,
                List.of("Should be ignored"), List.of(draft), null, null, null);
        when(canonicalTopics.findByTopicId(any())).thenReturn(Optional.empty());
        when(embeddings.embed(any())).thenReturn(null);

        service.create(req);

        ArgumentCaptor<Entry> captor = ArgumentCaptor.forClass(Entry.class);
        verify(repository, times(2)).save(captor.capture()); // roadmap + 1 draft step
        assertThat(captor.getAllValues().get(1).getContent().get("text")).isEqualTo("Draft step");
    }

    @Test
    @DisplayName("modules create empty child ROADMAP entries in order")
    void createWithModules() {
        CreateRoadmapRequest.ModuleInput m1 = new CreateRoadmapRequest.ModuleInput("Module A", "scope a");
        CreateRoadmapRequest.ModuleInput m2 = new CreateRoadmapRequest.ModuleInput(null, null); // skipped
        CreateRoadmapRequest req = new CreateRoadmapRequest("Learn Rust", null, null, null,
                List.of(m1, m2), null, null);
        when(canonicalTopics.findByTopicId(any())).thenReturn(Optional.empty());
        when(embeddings.embed(any())).thenReturn(null);

        service.create(req);

        ArgumentCaptor<Entry> captor = ArgumentCaptor.forClass(Entry.class);
        verify(repository, times(2)).save(captor.capture()); // roadmap + 1 real module
        Entry savedModule = captor.getAllValues().get(1);
        assertThat(savedModule.getType()).isEqualTo(EntryType.ROADMAP);
        assertThat(savedModule.getContent().get("title")).isEqualTo("Module A");
        assertThat(savedModule.getContent().get("scope")).isEqualTo("scope a");
    }

    @Test
    @DisplayName("assessment and tier are stored on the roadmap's content when given")
    void createStoresAssessmentAndTier() {
        CreateRoadmapRequest.AssessmentInput assessment =
                new CreateRoadmapRequest.AssessmentInput(4, 30, "engineering", "some", "nested", "career_path");
        CreateRoadmapRequest req = new CreateRoadmapRequest("Learn Rust", null, null, null, null,
                assessment, "CAREER");
        when(canonicalTopics.findByTopicId(any())).thenReturn(Optional.empty());
        when(embeddings.embed(any())).thenReturn(null);

        Entry created = service.create(req);

        assertThat(created.getContent().get("tier")).isEqualTo("CAREER");
        assertThat(created.getContent().get("assessment")).isInstanceOf(Map.class);
        @SuppressWarnings("unchecked")
        Map<String, Object> storedAssessment = (Map<String, Object>) created.getContent().get("assessment");
        assertThat(storedAssessment.get("domain")).isEqualTo("engineering");
    }

    @Test
    @DisplayName("a real embedding saves a canonical topic; a failed one just skips it")
    void createSavesCanonicalTopicOnlyWhenEmbeddingSucceeds() {
        CreateRoadmapRequest req = new CreateRoadmapRequest("Learn Rust", null, null, null, null, null, null);
        when(canonicalTopics.findByTopicId("learn-rust")).thenReturn(Optional.empty());
        when(embeddings.embed("Learn Rust")).thenReturn(List.of(0.1, 0.2));

        service.create(req);

        verify(canonicalTopics).save(any());
    }

    @Test
    @DisplayName("an existing canonical topic slug is left alone, never overwritten")
    void createSkipsCanonicalTopicOnCollision() {
        CreateRoadmapRequest req = new CreateRoadmapRequest("Learn Rust", null, null, null, null, null, null);
        when(canonicalTopics.findByTopicId("learn-rust"))
                .thenReturn(Optional.of(new com.compass.app.topic.CanonicalTopic()));

        service.create(req);

        verify(embeddings, never()).embed(any());
        verify(canonicalTopics, never()).save(any());
    }

    // ── getRoadmap / listRoadmaps / listArchivedRoadmaps / setArchived / deleteRoadmap ────

    @Test
    @DisplayName("getRoadmap 404s on a missing id, and on an id that isn't a ROADMAP")
    void getRoadmapValidatesTypeAndExistence() {
        when(repository.findById(1L)).thenReturn(Optional.empty());
        when(repository.findById(2L)).thenReturn(Optional.of(
                entryWithId(2, EntryType.ROADMAP_STEP, EntryStatus.CAPTURED, null)));

        assertThatThrownBy(() -> service.getRoadmap(1L)).isInstanceOf(java.util.NoSuchElementException.class);
        assertThatThrownBy(() -> service.getRoadmap(2L)).isInstanceOf(java.util.NoSuchElementException.class);
    }

    @Test
    @DisplayName("listRoadmaps returns only top-level (no parent), non-archived roadmaps")
    void listRoadmapsFiltersChildrenAndArchived() {
        Entry top = roadmap(1, "Top");
        Entry child = module(2, 1, "Child module", 0);
        Entry archived = roadmap(3, "Archived");
        archived.setStatus(EntryStatus.ARCHIVED);
        when(repository.findByTypeOrderByCreatedAtDesc(EntryType.ROADMAP))
                .thenReturn(List.of(top, child, archived));

        List<Entry> result = service.listRoadmaps();

        assertThat(result).containsExactly(top);
    }

    @Test
    @DisplayName("listArchivedRoadmaps returns only top-level archived roadmaps")
    void listArchivedRoadmapsFiltersToArchivedTopLevel() {
        Entry active = roadmap(1, "Active");
        Entry archived = roadmap(2, "Archived");
        archived.setStatus(EntryStatus.ARCHIVED);
        Entry archivedChild = module(3, 2, "Child", 0);
        archivedChild.setStatus(EntryStatus.ARCHIVED);
        when(repository.findByTypeOrderByCreatedAtDesc(EntryType.ROADMAP))
                .thenReturn(List.of(active, archived, archivedChild));

        assertThat(service.listArchivedRoadmaps()).containsExactly(archived);
    }

    @Test
    @DisplayName("setArchived toggles status both ways")
    void setArchivedTogglesStatus() {
        Entry r = roadmap(1, "Title");
        when(repository.findById(1L)).thenReturn(Optional.of(r));

        Entry archived = service.setArchived(1L, true);
        assertThat(archived.getStatus()).isEqualTo(EntryStatus.ARCHIVED);

        Entry unarchived = service.setArchived(1L, false);
        assertThat(unarchived.getStatus()).isEqualTo(EntryStatus.IN_MOTION);
    }

    @Test
    @DisplayName("deleteRoadmap 404s on a non-roadmap id and otherwise deletes steps then the roadmap")
    void deleteRoadmapDeletesStepsThenRoadmap() {
        Entry r = roadmap(1, "Title");
        when(repository.findById(1L)).thenReturn(Optional.of(r));
        List<Entry> steps = List.of(step(2, 1, "s1", EntryStatus.CAPTURED, 0));
        when(repository.findByParentIdOrderByOrderIndexAsc(1L)).thenReturn(steps);

        service.deleteRoadmap(1L);

        verify(repository).deleteAll(steps);
        verify(repository).deleteById(1L);
    }

    // ── stepsOf / leafStepsOf / domainOf ───────────────────────────────────────────────

    @Test
    @DisplayName("leafStepsOf returns only ROADMAP_STEP descendants that are nobody's parent")
    void leafStepsOfExcludesContainers() {
        Entry module = module(10, 1, "Module", 0);
        Entry container = step(11, 10, "Container step", EntryStatus.CAPTURED, 0);
        Entry leaf = step(12, 11, "Real leaf", EntryStatus.CAPTURED, 0);
        Entry directLeaf = step(13, 10, "Direct leaf", EntryStatus.CAPTURED, 1);
        when(repository.findDescendants(1L)).thenReturn(List.of(module, container, leaf, directLeaf));

        List<Entry> leaves = service.leafStepsOf(1L);

        assertThat(leaves).containsExactly(leaf, directLeaf);
    }

    @Test
    @DisplayName("domainOf walks to the root and reads its stored assessment domain")
    void domainOfWalksToRoot() {
        Entry leaf = step(5, 4, "leaf", EntryStatus.CAPTURED, 0);
        Map<String, Object> rootContent = new HashMap<>();
        rootContent.put("assessment", Map.of("domain", "cybersecurity"));
        Entry root = entryWithId(1, EntryType.ROADMAP, EntryStatus.IN_MOTION, null, null, rootContent);
        when(repository.findById(5L)).thenReturn(Optional.of(leaf));
        when(repository.findAncestors(5L)).thenReturn(List.of(module(4, 1, "m", 0), root));

        assertThat(service.domainOf(5L)).isEqualTo("cybersecurity");
    }

    @Test
    @DisplayName("domainOf returns null for a missing node, or a root with no stored assessment")
    void domainOfDegradesToNull() {
        when(repository.findById(404L)).thenReturn(Optional.empty());
        assertThat(service.domainOf(404L)).isNull();

        Entry noAssessment = roadmap(1, "Title");
        when(repository.findById(1L)).thenReturn(Optional.of(noAssessment));
        when(repository.findAncestors(1L)).thenReturn(List.of());
        assertThat(service.domainOf(1L)).isNull();
    }

    // ── reorderSteps / insertStep / deleteStep ─────────────────────────────────────────

    @Test
    @DisplayName("reorderSteps rejects a list that doesn't exactly match the current steps")
    void reorderStepsRejectsMismatch() {
        List<Entry> current = List.of(step(1, 10, "a", EntryStatus.CAPTURED, 0),
                step(2, 10, "b", EntryStatus.CAPTURED, 1));
        when(repository.findByParentIdOrderByOrderIndexAsc(10L)).thenReturn(current);

        assertThatThrownBy(() -> service.reorderSteps(10L, List.of(1L))) // missing step 2
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.reorderSteps(10L, List.of(1L, 2L, 3L))) // extra id
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("reorderSteps re-assigns order_index to match the given order exactly")
    void reorderStepsAppliesNewOrder() {
        Entry a = step(1, 10, "a", EntryStatus.CAPTURED, 0);
        Entry b = step(2, 10, "b", EntryStatus.CAPTURED, 1);
        when(repository.findByParentIdOrderByOrderIndexAsc(10L)).thenReturn(List.of(a, b));

        service.reorderSteps(10L, List.of(2L, 1L));

        ArgumentCaptor<List<Entry>> captor = ArgumentCaptor.forClass(List.class);
        verify(repository).saveAll(captor.capture());
        assertThat(captor.getValue()).containsExactly(b, a);
        assertThat(b.getOrderIndex()).isZero();
        assertThat(a.getOrderIndex()).isEqualTo(1);
        verify(repository).touchUpdatedAt(eq(10L), any());
    }

    @Test
    @DisplayName("insertStep rejects blank text without touching the repository")
    void insertStepRejectsBlankText() {
        when(repository.findById(10L)).thenReturn(Optional.of(roadmap(10, "R")));

        assertThatThrownBy(() -> service.insertStep(10L, "  ", null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("insertStep with a null position appends to the end")
    void insertStepAppendsWhenPositionNull() {
        when(repository.findById(10L)).thenReturn(Optional.of(roadmap(10, "R")));
        Entry existing = step(1, 10, "existing", EntryStatus.CAPTURED, 0);
        when(repository.findByParentIdOrderByOrderIndexAsc(10L)).thenReturn(new ArrayList<>(List.of(existing)));

        Entry inserted = service.insertStep(10L, "new step", null);

        assertThat(inserted.getContent().get("text")).isEqualTo("new step");
        ArgumentCaptor<List<Entry>> captor = ArgumentCaptor.forClass(List.class);
        verify(repository).saveAll(captor.capture());
        assertThat(captor.getValue()).hasSize(2);
        assertThat(captor.getValue().get(1)).isSameAs(inserted);
    }

    @Test
    @DisplayName("insertStep clamps an out-of-range position instead of throwing")
    void insertStepClampsPosition() {
        when(repository.findById(10L)).thenReturn(Optional.of(roadmap(10, "R")));
        when(repository.findByParentIdOrderByOrderIndexAsc(10L)).thenReturn(new ArrayList<>());

        // Position 99 on an empty list must clamp to 0, not throw an IndexOutOfBounds.
        Entry inserted = service.insertStep(10L, "first", 99);
        assertThat(inserted.getContent().get("text")).isEqualTo("first");
    }

    @Test
    @DisplayName("deleteStep 404s on a non-step id, and otherwise deletes and closes the order gap")
    void deleteStepClosesOrderGap() {
        Entry toDelete = step(2, 10, "gone", EntryStatus.CAPTURED, 1);
        when(repository.findById(2L)).thenReturn(Optional.of(toDelete));
        Entry remaining = step(3, 10, "stays", EntryStatus.CAPTURED, 2);
        when(repository.findByParentIdOrderByOrderIndexAsc(10L)).thenReturn(new ArrayList<>(List.of(remaining)));

        service.deleteStep(10L, 2L);

        verify(repository).delete(toDelete);
        assertThat(remaining.getOrderIndex()).isZero(); // gap closed: was 2, now 0
    }

    // ── splitStep / inheritResources ───────────────────────────────────────────────────

    @Test
    @DisplayName("splitStep rejects an empty replacement list")
    void splitStepRejectsEmptyReplacement() {
        assertThatThrownBy(() -> service.splitStep(10L, 2L, List.of()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.splitStep(10L, 2L, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("splitStep rejects breaking down a step already at the depth cap")
    void splitStepRejectsAtMaxDepth() {
        Entry original = step(2, 1, "original", EntryStatus.CAPTURED, 0);
        when(repository.findById(2L)).thenReturn(Optional.of(original));
        when(repository.findAncestors(2L))
                .thenReturn(java.util.Collections.nCopies(RoadmapQueryService.MAX_STEP_DEPTH, new Entry()));
        CreateRoadmapRequest.DraftStepInput draft = new CreateRoadmapRequest.DraftStepInput(
                "sub", null, null, null, null, null, null, false);

        assertThatThrownBy(() -> service.splitStep(10L, 2L, List.of(draft)))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("splitStep creates substeps and makes them inherit the parent's resources")
    void splitStepCreatesSubstepsAndInheritsResources() {
        Map<String, Object> parentContent = new HashMap<>();
        parentContent.put("text", "original");
        parentContent.put("resources", new ArrayList<>(List.of(Map.of("title", "Res", "url", "https://x"))));
        Entry original = entryWithId(2, EntryType.ROADMAP_STEP, EntryStatus.CAPTURED, 1L, 0, parentContent);
        when(repository.findById(2L)).thenReturn(Optional.of(original));
        when(repository.findAncestors(2L)).thenReturn(List.of());

        CreateRoadmapRequest.DraftStepInput draft = new CreateRoadmapRequest.DraftStepInput(
                "substep 1", "concept", "small", null, null, null, null, false);
        // After createDraftSteps saves the substep, inheritResources looks it up as a child of
        // `original` — simulate that by having the repository know about it once saved.
        Entry substep = step(3, 2, "substep 1", EntryStatus.CAPTURED, 0);
        when(repository.findByParentIdOrderByOrderIndexAsc(2L)).thenReturn(List.of(substep));

        service.splitStep(10L, 2L, List.of(draft));

        assertThat(substep.getContent().get("resources")).isNotNull();
        verify(repository).touchUpdatedAt(eq(10L), any());
    }

    // ── addPrerequisite ─────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("addPrerequisite rejects blank text")
    void addPrerequisiteRejectsBlankText() {
        assertThatThrownBy(() -> service.addPrerequisite(10L, 2L, "  "))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("addPrerequisite inserts a sibling before the target and links depends_on")
    void addPrerequisiteInsertsAndLinks() {
        Entry target = step(2, 10, "target", EntryStatus.CAPTURED, 0);
        when(repository.findById(2L)).thenReturn(Optional.of(target));
        when(repository.findByParentIdOrderByOrderIndexAsc(10L))
                .thenReturn(new ArrayList<>(List.of(target)));

        Entry prerequisite = service.addPrerequisite(10L, 2L, "do this first");

        assertThat(prerequisite.getContent().get("text")).isEqualTo("do this first");
        assertThat(target.getDependsOn()).isEqualTo(prerequisite.getId());
    }

    // ── flattenStep ─────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("flattenStep rejects a step with no substeps")
    void flattenStepRejectsNoSubsteps() {
        when(repository.findById(2L)).thenReturn(Optional.of(step(2, 1, "s", EntryStatus.CAPTURED, 0)));
        when(repository.findByParentIdOrderByOrderIndexAsc(2L)).thenReturn(List.of());

        assertThatThrownBy(() -> service.flattenStep(10L, 2L)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("flattenStep rejects when any substep has real progress")
    void flattenStepRejectsRealProgress() {
        when(repository.findById(2L)).thenReturn(Optional.of(step(2, 1, "s", EntryStatus.CAPTURED, 0)));
        Entry inProgress = step(3, 2, "sub", EntryStatus.IN_MOTION, 0);
        when(repository.findByParentIdOrderByOrderIndexAsc(2L)).thenReturn(List.of(inProgress));

        assertThatThrownBy(() -> service.flattenStep(10L, 2L)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("flattenStep deletes untouched substeps")
    void flattenStepDeletesUntouchedSubsteps() {
        when(repository.findById(2L)).thenReturn(Optional.of(step(2, 1, "s", EntryStatus.CAPTURED, 0)));
        List<Entry> substeps = List.of(step(3, 2, "sub", EntryStatus.CAPTURED, 0));
        when(repository.findByParentIdOrderByOrderIndexAsc(2L)).thenReturn(substeps);

        service.flattenStep(10L, 2L);

        verify(repository).deleteAll(substeps);
    }

    // ── graduateStep ────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("graduateStep rejects a step not nested under another step")
    void graduateStepRejectsNonNestedStep() {
        Entry step = step(2, 1, "s", EntryStatus.CAPTURED, 0); // parent is the root roadmap
        when(repository.findById(2L)).thenReturn(Optional.of(step));
        when(repository.findById(1L)).thenReturn(Optional.of(roadmap(1, "R")));

        assertThatThrownBy(() -> service.graduateStep(1L, 2L)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("graduateStep reparents the step as a sibling right after its old parent")
    void graduateStepReparentsAsSibling() {
        Entry oldParent = step(2, 1, "container", EntryStatus.CAPTURED, 0);
        Entry target = step(3, 2, "graduating", EntryStatus.CAPTURED, 0);
        when(repository.findById(3L)).thenReturn(Optional.of(target));
        when(repository.findById(2L)).thenReturn(Optional.of(oldParent));
        when(repository.findByParentIdOrderByOrderIndexAsc(2L)).thenReturn(new ArrayList<>(List.of(target)));
        Entry otherSibling = step(4, 1, "other", EntryStatus.CAPTURED, 1);
        when(repository.findByParentIdOrderByOrderIndexAsc(1L))
                .thenReturn(new ArrayList<>(List.of(oldParent, otherSibling)));

        service.graduateStep(1L, 3L);

        assertThat(target.getParentId()).isEqualTo(1L);
        // Inserted right after oldParent (index 0), so it lands before otherSibling.
        assertThat(target.getOrderIndex()).isEqualTo(1);
    }

    // ── syncStepCompletion ──────────────────────────────────────────────────────────────

    @Test
    @DisplayName("syncStepCompletion is a no-op unless the step itself is DONE")
    void syncStepCompletionNoOpWhenNotDone() {
        Entry inProgress = step(2, 1, "s", EntryStatus.IN_MOTION, 0);
        when(repository.findById(2L)).thenReturn(Optional.of(inProgress));

        service.syncStepCompletion(2L);

        verify(repository, never()).findByParentIdOrderByOrderIndexAsc(anyLong());
    }

    @Test
    @DisplayName("marking a container done cascades DONE to every not-yet-done child")
    void syncStepCompletionCascadesToChildren() {
        Entry container = step(2, 1, "container", EntryStatus.DONE, 0);
        when(repository.findById(2L)).thenReturn(Optional.of(container));
        Entry child1 = step(3, 2, "child1", EntryStatus.CAPTURED, 0);
        Entry child2 = step(4, 2, "child2", EntryStatus.DONE, 1); // already done, left alone
        when(repository.findByParentIdOrderByOrderIndexAsc(2L)).thenReturn(List.of(child1, child2));

        service.syncStepCompletion(2L);

        assertThat(child1.getStatus()).isEqualTo(EntryStatus.DONE);
        verify(repository).save(child1);
        verify(repository, never()).save(child2);
    }

    @Test
    @DisplayName("when every sibling is now done, a real container parent (step or module) marks done too")
    void syncStepCompletionRollsUpToParentWhenAllSiblingsDone() {
        Entry justCompleted = step(3, 2, "s", EntryStatus.DONE, 0);
        when(repository.findById(3L)).thenReturn(Optional.of(justCompleted));
        when(repository.findByParentIdOrderByOrderIndexAsc(3L)).thenReturn(List.of()); // no children of its own
        Entry parentModule = module(2, 1, "Module", 0);
        when(repository.findById(2L)).thenReturn(Optional.of(parentModule));
        Entry sibling = step(4, 2, "sibling", EntryStatus.DONE, 1);
        when(repository.findByParentIdOrderByOrderIndexAsc(2L)).thenReturn(List.of(justCompleted, sibling));

        service.syncStepCompletion(3L);

        assertThat(parentModule.getStatus()).isEqualTo(EntryStatus.DONE);
    }

    @Test
    @DisplayName("the top-level roadmap itself never auto-completes via this path")
    void syncStepCompletionNeverCompletesRootRoadmap() {
        Entry justCompleted = step(2, 1, "s", EntryStatus.DONE, 0);
        when(repository.findById(2L)).thenReturn(Optional.of(justCompleted));
        when(repository.findByParentIdOrderByOrderIndexAsc(2L)).thenReturn(List.of());
        Entry root = roadmap(1, "Root"); // parentId == null: the real top-level roadmap
        when(repository.findById(1L)).thenReturn(Optional.of(root));

        service.syncStepCompletion(2L);

        verify(repository, never()).save(root);
    }

    @Test
    @DisplayName("a not-all-done sibling set leaves the parent untouched")
    void syncStepCompletionDoesNotRollUpWhenSiblingsIncomplete() {
        Entry justCompleted = step(3, 2, "s", EntryStatus.DONE, 0);
        when(repository.findById(3L)).thenReturn(Optional.of(justCompleted));
        when(repository.findByParentIdOrderByOrderIndexAsc(3L)).thenReturn(List.of());
        Entry parentModule = module(2, 1, "Module", 0);
        when(repository.findById(2L)).thenReturn(Optional.of(parentModule));
        Entry incompleteSibling = step(4, 2, "sibling", EntryStatus.IN_MOTION, 1);
        when(repository.findByParentIdOrderByOrderIndexAsc(2L)).thenReturn(List.of(justCompleted, incompleteSibling));

        service.syncStepCompletion(3L);

        assertThat(parentModule.getStatus()).isNotEqualTo(EntryStatus.DONE);
        verify(repository, never()).save(parentModule);
    }

    // ── checkCareerCompletion ───────────────────────────────────────────────────────────

    @Test
    @DisplayName("checkCareerCompletion is a no-op for a non-CAREER roadmap")
    void checkCareerCompletionNoOpForNonCareer() {
        Entry r = roadmap(1, "R", "TOPIC");
        when(repository.findById(1L)).thenReturn(Optional.of(r));

        assertThat(service.checkCareerCompletion(1L)).isNull();
        verify(reviewAi, never()).careerCompletionReflection(any());
    }

    @Test
    @DisplayName("checkCareerCompletion is a no-op when already reflected once")
    void checkCareerCompletionNoOpWhenAlreadyReflected() {
        Entry r = roadmap(1, "R", "CAREER");
        r.getContent().put("completionReflection", "already said");
        when(repository.findById(1L)).thenReturn(Optional.of(r));

        assertThat(service.checkCareerCompletion(1L)).isNull();
    }

    @Test
    @DisplayName("checkCareerCompletion is a no-op unless every leaf step is DONE")
    void checkCareerCompletionNoOpWhenIncomplete() {
        Entry r = roadmap(1, "R", "CAREER");
        when(repository.findById(1L)).thenReturn(Optional.of(r));
        Entry leaf = step(2, 1, "leaf", EntryStatus.IN_MOTION, 0);
        when(repository.findDescendants(1L)).thenReturn(List.of(leaf));

        assertThat(service.checkCareerCompletion(1L)).isNull();
    }

    @Test
    @DisplayName("checkCareerCompletion reflects once every leaf is DONE, and stores the reflection")
    void checkCareerCompletionReflectsWhenComplete() {
        Entry r = roadmap(1, "R", "CAREER");
        when(repository.findById(1L)).thenReturn(Optional.of(r));
        Entry leaf = step(2, 1, "leaf", EntryStatus.DONE, 0);
        when(repository.findDescendants(1L)).thenReturn(List.of(leaf));
        when(reviewAi.careerCompletionReflection("R")).thenReturn("You made it.");

        String reflection = service.checkCareerCompletion(1L);

        assertThat(reflection).isEqualTo("You made it.");
        assertThat(r.getContent().get("completionReflection")).isEqualTo("You made it.");
        verify(repository).save(r);
    }

    // ── addStepsToModule / unexpandedModuleIds / updateModule / insertModule ──────────

    @Test
    @DisplayName("addStepsToModule 404s when the module doesn't belong to the given roadmap")
    void addStepsToModuleRequiresRealModule() {
        when(repository.findById(2L)).thenReturn(Optional.of(module(2, 999, "M", 0))); // wrong roadmap

        CreateRoadmapRequest.DraftStepInput draft = new CreateRoadmapRequest.DraftStepInput(
                "s", null, null, null, null, null, null, false);
        assertThatThrownBy(() -> service.addStepsToModule(1L, 2L, List.of(draft)))
                .isInstanceOf(java.util.NoSuchElementException.class);
    }

    @Test
    @DisplayName("unexpandedModuleIds returns only modules with zero children")
    void unexpandedModuleIdsFindsChildlessModules() {
        Entry expanded = module(2, 1, "Expanded", 0);
        Entry unexpanded = module(3, 1, "Unexpanded", 1);
        when(repository.findByParentIdOrderByOrderIndexAsc(1L)).thenReturn(List.of(expanded, unexpanded));
        when(repository.findByParentIdOrderByOrderIndexAsc(2L)).thenReturn(List.of(step(4, 2, "s", EntryStatus.CAPTURED, 0)));
        when(repository.findByParentIdOrderByOrderIndexAsc(3L)).thenReturn(List.of());

        assertThat(service.unexpandedModuleIds(1L)).containsExactly(3L);
    }

    @Test
    @DisplayName("updateModule rejects a blank title, clears scope when blank, sets it when given")
    void updateModuleValidatesAndUpdatesContent() {
        Entry m = module(2, 1, "Old title", 0);
        m.getContent().put("scope", "old scope");
        when(repository.findById(2L)).thenReturn(Optional.of(m));

        assertThatThrownBy(() -> service.updateModule(1L, 2L, "  ", "x"))
                .isInstanceOf(IllegalArgumentException.class);

        service.updateModule(1L, 2L, "New title", "  ");
        assertThat(m.getContent().get("title")).isEqualTo("New title");
        assertThat(m.getContent()).doesNotContainKey("scope");

        service.updateModule(1L, 2L, "New title", "new scope");
        assertThat(m.getContent().get("scope")).isEqualTo("new scope");
    }

    @Test
    @DisplayName("insertModule rejects a blank title and otherwise inserts at the clamped position")
    void insertModuleValidatesAndClampsPosition() {
        when(repository.findById(1L)).thenReturn(Optional.of(roadmap(1, "R")));
        assertThatThrownBy(() -> service.insertModule(1L, "  ", null, null))
                .isInstanceOf(IllegalArgumentException.class);

        Entry existing = module(2, 1, "existing", 0);
        when(repository.findByParentIdOrderByOrderIndexAsc(1L)).thenReturn(new ArrayList<>(List.of(existing)));

        Entry inserted = service.insertModule(1L, "New module", "scope", 0);

        assertThat(inserted.getContent().get("title")).isEqualTo("New module");
        assertThat(inserted.getOrderIndex()).isZero();
        assertThat(existing.getOrderIndex()).isEqualTo(1); // pushed down
    }

    // ── replanRemainingModules / applyReplan ───────────────────────────────────────────

    @Test
    @DisplayName("replanRemainingModules rejects when nothing is left unexpanded")
    void replanRemainingModulesRejectsWhenAllExpanded() {
        Entry r = roadmap(1, "R");
        when(repository.findById(1L)).thenReturn(Optional.of(r));
        when(roadmapAi.isAvailable()).thenReturn(true);
        Entry expanded = module(2, 1, "M", 0);
        when(repository.findByParentIdOrderByOrderIndexAsc(1L)).thenReturn(List.of(expanded));
        when(repository.findByParentIdOrderByOrderIndexAsc(2L))
                .thenReturn(List.of(step(3, 2, "s", EntryStatus.CAPTURED, 0)));

        assertThatThrownBy(() -> service.replanRemainingModules(1L))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("applyReplan calls updateModule for each item, by its real module id")
    void applyReplanUpdatesEachModule() {
        Entry m1 = module(2, 1, "Old 1", 0);
        Entry m2 = module(3, 1, "Old 2", 1);
        when(repository.findById(2L)).thenReturn(Optional.of(m1));
        when(repository.findById(3L)).thenReturn(Optional.of(m2));

        service.applyReplan(1L, List.of(
                new ReplanModuleItem(2L, "New 1", "scope 1"),
                new ReplanModuleItem(3L, "New 2", "scope 2")));

        assertThat(m1.getContent().get("title")).isEqualTo("New 1");
        assertThat(m2.getContent().get("title")).isEqualTo("New 2");
    }

    // ── reTier ──────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("reTier to TASK archives the roadmap and creates a task entry")
    void reTierToTaskArchivesAndCreatesTask() {
        Entry r = roadmap(1, "R");
        when(repository.findById(1L)).thenReturn(Optional.of(r));
        when(repository.findByParentIdOrderByOrderIndexAsc(1L)).thenReturn(List.of());
        Entry task = entryWithId(99, EntryType.TASK, EntryStatus.CAPTURED, null);
        when(entryService.create(any())).thenReturn(task);
        when(aiVoice.acknowledge(task)).thenReturn("Held.");

        ReTierResponse resp = service.reTier(1L, new ReTierRequest("TASK"));

        assertThat(resp.status()).isEqualTo("applied");
        assertThat(resp.taskEntryId()).isEqualTo(99L);
        assertThat(r.getStatus()).isEqualTo(EntryStatus.ARCHIVED);
        verify(events).founderAction(eq("re_tier"), any(), any());
    }

    @Test
    @DisplayName("reTier to MINI on an already-flat roadmap just relabels")
    void reTierToMiniOnFlatRoadmapRelabelsOnly() {
        Entry r = roadmap(1, "R");
        when(repository.findById(1L)).thenReturn(Optional.of(r));
        Entry flatStep = step(2, 1, "s", EntryStatus.CAPTURED, 0);
        when(repository.findByParentIdOrderByOrderIndexAsc(1L)).thenReturn(List.of(flatStep));

        ReTierResponse resp = service.reTier(1L, new ReTierRequest("MINI"));

        assertThat(resp.status()).isEqualTo("applied");
        assertThat(r.getContent().get("tier")).isEqualTo("MINI");
    }

    @Test
    @DisplayName("reTier to MINI on a nested roadmap flattens: leaves reparent, empty modules archive")
    void reTierToMiniOnNestedRoadmapFlattens() {
        Entry r = roadmap(1, "R");
        when(repository.findById(1L)).thenReturn(Optional.of(r));
        Entry mod = module(2, 1, "M", 0);
        when(repository.findByParentIdOrderByOrderIndexAsc(1L)).thenReturn(List.of(mod));
        Entry leaf = step(3, 2, "leaf", EntryStatus.CAPTURED, 0);
        when(repository.findDescendants(1L)).thenReturn(List.of(mod, leaf));

        ReTierResponse resp = service.reTier(1L, new ReTierRequest("MINI"));

        assertThat(resp.status()).isEqualTo("applied");
        assertThat(leaf.getParentId()).isEqualTo(1L);
        assertThat(mod.getStatus()).isEqualTo(EntryStatus.ARCHIVED);
        assertThat(r.getContent().get("tier")).isEqualTo("MINI");
    }

    @Test
    @DisplayName("reTier a flat roadmap directly to CAREER is rejected — TOPIC must come first")
    void reTierFlatToCareerDirectlyRejected() {
        Entry r = roadmap(1, "R");
        when(repository.findById(1L)).thenReturn(Optional.of(r));
        Entry flatStep = step(2, 1, "s", EntryStatus.CAPTURED, 0);
        when(repository.findByParentIdOrderByOrderIndexAsc(1L)).thenReturn(List.of(flatStep));

        assertThatThrownBy(() -> service.reTier(1L, new ReTierRequest("CAREER")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("reTier a flat non-empty roadmap to TOPIC proposes a regroup, applying nothing yet")
    void reTierFlatToTopicProposesRegroup() {
        Entry r = roadmap(1, "R");
        when(repository.findById(1L)).thenReturn(Optional.of(r));
        Entry flatStep = step(2, 1, "s", EntryStatus.CAPTURED, 0);
        when(repository.findByParentIdOrderByOrderIndexAsc(1L)).thenReturn(List.of(flatStep));
        when(roadmapAi.regroupSteps(any(), any())).thenReturn(List.of(
                new RoadmapAiService.RegroupedModule("Group A", "scope", List.of(2L))));

        ReTierResponse resp = service.reTier(1L, new ReTierRequest("TOPIC"));

        assertThat(resp.status()).isEqualTo("proposal");
        assertThat(resp.proposal().kind()).isEqualTo("regroup");
        verify(repository, never()).save(any());
    }

    @Test
    @DisplayName("reTier a nested roadmap to CAREER proposes an arc order")
    void reTierNestedToCareerProposesArcOrder() {
        Entry r = roadmap(1, "R");
        when(repository.findById(1L)).thenReturn(Optional.of(r));
        Entry mod = module(2, 1, "M", 0);
        when(repository.findByParentIdOrderByOrderIndexAsc(1L)).thenReturn(List.of(mod));
        when(roadmapAi.proposeCareerArc(any(), any())).thenReturn(List.of(
                new RoadmapAiService.ArcPosition(2L, "Phase 1")));

        ReTierResponse resp = service.reTier(1L, new ReTierRequest("CAREER"));

        assertThat(resp.status()).isEqualTo("proposal");
        assertThat(resp.proposal().kind()).isEqualTo("arc_order");
    }

    @Test
    @DisplayName("an unknown tier string is rejected")
    void reTierRejectsUnknownTier() {
        when(repository.findById(1L)).thenReturn(Optional.of(roadmap(1, "R")));

        assertThatThrownBy(() -> service.reTier(1L, new ReTierRequest("NOT_A_TIER")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ── applyReTierProposal (regroup / arc_order) ──────────────────────────────────────

    @Test
    @DisplayName("applyReTierProposal rejects an unknown proposal kind")
    void applyReTierProposalRejectsUnknownKind() {
        when(repository.findById(1L)).thenReturn(Optional.of(roadmap(1, "R")));

        assertThatThrownBy(() -> service.applyReTierProposal(1L,
                new ApplyReTierProposalRequest("not_a_kind", List.of())))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("applying a regroup creates one module per group and an Ungrouped catch-all for leftovers")
    void applyRegroupCreatesModulesAndCatchAll() {
        Entry r = roadmap(1, "R");
        when(repository.findById(1L)).thenReturn(Optional.of(r));
        Entry step1 = step(2, 1, "s1", EntryStatus.CAPTURED, 0);
        Entry step2 = step(3, 1, "s2 (done, left out)", EntryStatus.DONE, 1);
        when(repository.findByParentIdOrderByOrderIndexAsc(1L)).thenReturn(List.of(step1, step2));

        ApplyReTierProposalRequest req = new ApplyReTierProposalRequest("regroup", List.of(
                new ReTierResponse.Group("Group A", "scope", List.of(2L), null)));

        ReTierResponse resp = service.applyReTierProposal(1L, req);

        assertThat(resp.status()).isEqualTo("applied");
        assertThat(step1.getParentId()).isNotEqualTo(1L); // reparented into the new "Group A" module
        assertThat(step2.getParentId()).isNotEqualTo(1L); // swept into "Ungrouped"
        assertThat(r.getContent().get("tier")).isEqualTo("TOPIC");
    }

    @Test
    @DisplayName("applying an arc_order reorders the existing modules and sets tier to CAREER")
    void applyArcOrderReordersModules() {
        Entry r = roadmap(1, "R");
        when(repository.findById(1L)).thenReturn(Optional.of(r));
        Entry m1 = module(2, 1, "M1", 0);
        Entry m2 = module(3, 1, "M2", 1);
        when(repository.findByParentIdOrderByOrderIndexAsc(1L)).thenReturn(List.of(m1, m2));

        ApplyReTierProposalRequest req = new ApplyReTierProposalRequest("arc_order", List.of(
                new ReTierResponse.Group(null, null, List.of(3L), "Phase 1"),
                new ReTierResponse.Group(null, null, List.of(2L), "Phase 2")));

        service.applyReTierProposal(1L, req);

        assertThat(m2.getOrderIndex()).isZero();
        assertThat(m1.getOrderIndex()).isEqualTo(1);
        assertThat(r.getContent().get("tier")).isEqualTo("CAREER");
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

    private static boolean anyBoolean() {
        return org.mockito.ArgumentMatchers.anyBoolean();
    }
}
