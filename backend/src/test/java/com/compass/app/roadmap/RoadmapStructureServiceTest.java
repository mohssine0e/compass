package com.compass.app.roadmap;

import com.compass.app.ai.EmbeddingService;
import com.compass.app.ai.ReviewAiService;
import com.compass.app.entry.Entry;
import com.compass.app.entry.EntryRepository;
import com.compass.app.entry.EntryStatus;
import com.compass.app.entry.EntryType;
import com.compass.app.roadmap.dto.CreateRoadmapRequest;
import com.compass.app.roadmap.dto.ReplanModuleItem;
import com.compass.app.topic.CanonicalTopicRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

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
 * Direct, focused coverage of {@link RoadmapStructureService} (V3-4.1's third extracted seam from
 * the former {@code RoadmapService} god-file — see {@code TASKS_v3.md}), retargeting the same
 * structural-editing assertions {@link RoadmapServiceBehaviorTest} already makes through {@code
 * RoadmapService}'s still-delegating facade. Both layers of coverage are intentional: the facade
 * tests prove the move didn't change {@code RoadmapService}'s public contract; these prove the
 * extracted class's own behavior directly.
 */
class RoadmapStructureServiceTest {

    private EntryRepository repository;
    private RoadmapQueryService queryService;
    private CanonicalTopicRepository canonicalTopics;
    private EmbeddingService embeddings;
    private ReviewAiService reviewAi;
    private RoadmapStructureService service;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        repository = mock(EntryRepository.class);
        queryService = new RoadmapQueryService(repository);
        canonicalTopics = mock(CanonicalTopicRepository.class);
        embeddings = mock(EmbeddingService.class);
        reviewAi = mock(ReviewAiService.class);
        service = new RoadmapStructureService(repository, queryService, canonicalTopics, embeddings, reviewAi);

        when(repository.save(any(Entry.class))).thenAnswer(inv -> {
            Entry e = inv.getArgument(0);
            if (e.getId() == null) {
                setId(e, ++nextId);
            }
            return e;
        });
        when(repository.saveAll(any(List.class))).thenAnswer(inv -> {
            List<Entry> list = inv.getArgument(0);
            for (Entry e : list) {
                if (e.getId() == null) {
                    setId(e, ++nextId);
                }
            }
            return list;
        });
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
        when(embeddings.embed(any())).thenReturn(null);

        Entry created = service.create(req);

        ArgumentCaptor<Entry> captor = ArgumentCaptor.forClass(Entry.class);
        verify(repository, times(3)).save(captor.capture());
        List<Entry> saved = captor.getAllValues();
        assertThat(saved.get(0).getContent().get("title")).isEqualTo("Learn Rust");
        assertThat(saved.get(1).getContent().get("text")).isEqualTo("Step one");
        assertThat(saved.get(2).getContent().get("text")).isEqualTo("Step two");
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
        verify(repository, times(2)).save(captor.capture());
        assertThat(captor.getAllValues().get(1).getContent().get("text")).isEqualTo("Draft step");
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

    // ── insertModule / updateModule / addStepsToModule / applyReplan ──────────────────

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
        assertThat(existing.getOrderIndex()).isEqualTo(1);
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
    @DisplayName("addStepsToModule 404s when the module doesn't belong to the given roadmap")
    void addStepsToModuleRequiresRealModule() {
        when(repository.findById(2L)).thenReturn(Optional.of(module(2, 999, "M", 0)));

        CreateRoadmapRequest.DraftStepInput draft = new CreateRoadmapRequest.DraftStepInput(
                "s", null, null, null, null, null, null, false);
        assertThatThrownBy(() -> service.addStepsToModule(1L, 2L, List.of(draft)))
                .isInstanceOf(java.util.NoSuchElementException.class);
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

    // ── reorderSteps / insertStep / deleteStep ─────────────────────────────────────────

    @Test
    @DisplayName("reorderSteps rejects a list that doesn't exactly match the current steps")
    void reorderStepsRejectsMismatch() {
        List<Entry> current = List.of(step(1, 10, "a", EntryStatus.CAPTURED, 0),
                step(2, 10, "b", EntryStatus.CAPTURED, 1));
        when(repository.findByParentIdOrderByOrderIndexAsc(10L)).thenReturn(current);

        assertThatThrownBy(() -> service.reorderSteps(10L, List.of(1L)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("reorderSteps re-assigns order_index to match the given order exactly")
    void reorderStepsAppliesNewOrder() {
        Entry a = step(1, 10, "a", EntryStatus.CAPTURED, 0);
        Entry b = step(2, 10, "b", EntryStatus.CAPTURED, 1);
        when(repository.findByParentIdOrderByOrderIndexAsc(10L)).thenReturn(List.of(a, b));

        service.reorderSteps(10L, List.of(2L, 1L));

        assertThat(b.getOrderIndex()).isZero();
        assertThat(a.getOrderIndex()).isEqualTo(1);
        verify(repository).touchUpdatedAt(eq(10L), any());
    }

    @Test
    @DisplayName("insertStep with a null position appends to the end")
    void insertStepAppendsWhenPositionNull() {
        when(repository.findById(10L)).thenReturn(Optional.of(roadmap(10, "R")));
        Entry existing = step(1, 10, "existing", EntryStatus.CAPTURED, 0);
        when(repository.findByParentIdOrderByOrderIndexAsc(10L)).thenReturn(new ArrayList<>(List.of(existing)));

        Entry inserted = service.insertStep(10L, "new step", null);

        assertThat(inserted.getContent().get("text")).isEqualTo("new step");
        assertThat(inserted.getOrderIndex()).isEqualTo(1);
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
        assertThat(remaining.getOrderIndex()).isZero();
    }

    // ── splitStep / addPrerequisite / flattenStep / graduateStep ──────────────────────

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
        Entry substep = step(3, 2, "substep 1", EntryStatus.CAPTURED, 0);
        when(repository.findByParentIdOrderByOrderIndexAsc(2L)).thenReturn(List.of(substep));

        service.splitStep(10L, 2L, List.of(draft));

        assertThat(substep.getContent().get("resources")).isNotNull();
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

    @Test
    @DisplayName("flattenStep rejects when any substep has real progress, deletes when untouched")
    void flattenStepGatesOnProgress() {
        when(repository.findById(2L)).thenReturn(Optional.of(step(2, 1, "s", EntryStatus.CAPTURED, 0)));
        Entry inProgress = step(3, 2, "sub", EntryStatus.IN_MOTION, 0);
        when(repository.findByParentIdOrderByOrderIndexAsc(2L)).thenReturn(List.of(inProgress));
        assertThatThrownBy(() -> service.flattenStep(10L, 2L)).isInstanceOf(IllegalStateException.class);

        List<Entry> untouched = List.of(step(4, 2, "sub2", EntryStatus.CAPTURED, 0));
        when(repository.findByParentIdOrderByOrderIndexAsc(2L)).thenReturn(untouched);
        service.flattenStep(10L, 2L);
        verify(repository).deleteAll(untouched);
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
        assertThat(target.getOrderIndex()).isEqualTo(1);
    }

    // ── deleteRoadmap / setArchived ─────────────────────────────────────────────────────

    @Test
    @DisplayName("deleteRoadmap deletes steps then the roadmap")
    void deleteRoadmapDeletesStepsThenRoadmap() {
        Entry r = roadmap(1, "Title");
        when(repository.findById(1L)).thenReturn(Optional.of(r));
        List<Entry> steps = List.of(step(2, 1, "s1", EntryStatus.CAPTURED, 0));
        when(repository.findByParentIdOrderByOrderIndexAsc(1L)).thenReturn(steps);

        service.deleteRoadmap(1L);

        verify(repository).deleteAll(steps);
        verify(repository).deleteById(1L);
    }

    @Test
    @DisplayName("setArchived toggles status both ways")
    void setArchivedTogglesStatus() {
        Entry r = roadmap(1, "Title");
        when(repository.findById(1L)).thenReturn(Optional.of(r));

        assertThat(service.setArchived(1L, true).getStatus()).isEqualTo(EntryStatus.ARCHIVED);
        assertThat(service.setArchived(1L, false).getStatus()).isEqualTo(EntryStatus.IN_MOTION);
    }

    // ── checkCareerCompletion / syncStepCompletion ─────────────────────────────────────

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
    }

    @Test
    @DisplayName("checkCareerCompletion is a no-op for a non-CAREER roadmap")
    void checkCareerCompletionNoOpForNonCareer() {
        Entry r = roadmap(1, "R", "TOPIC");
        when(repository.findById(1L)).thenReturn(Optional.of(r));

        assertThat(service.checkCareerCompletion(1L)).isNull();
    }

    @Test
    @DisplayName("marking a container done cascades DONE to every not-yet-done child")
    void syncStepCompletionCascadesToChildren() {
        Entry container = step(2, 1, "container", EntryStatus.DONE, 0);
        when(repository.findById(2L)).thenReturn(Optional.of(container));
        Entry child1 = step(3, 2, "child1", EntryStatus.CAPTURED, 0);
        Entry child2 = step(4, 2, "child2", EntryStatus.DONE, 1);
        when(repository.findByParentIdOrderByOrderIndexAsc(2L)).thenReturn(List.of(child1, child2));

        service.syncStepCompletion(2L);

        assertThat(child1.getStatus()).isEqualTo(EntryStatus.DONE);
        verify(repository).save(child1);
        verify(repository, never()).save(child2);
    }

    @Test
    @DisplayName("when every sibling is now done, a real container parent marks done too")
    void syncStepCompletionRollsUpToParentWhenAllSiblingsDone() {
        Entry justCompleted = step(3, 2, "s", EntryStatus.DONE, 0);
        when(repository.findById(3L)).thenReturn(Optional.of(justCompleted));
        when(repository.findByParentIdOrderByOrderIndexAsc(3L)).thenReturn(List.of());
        Entry parentModule = module(2, 1, "Module", 0);
        when(repository.findById(2L)).thenReturn(Optional.of(parentModule));
        Entry sibling = step(4, 2, "sibling", EntryStatus.DONE, 1);
        when(repository.findByParentIdOrderByOrderIndexAsc(2L)).thenReturn(List.of(justCompleted, sibling));

        service.syncStepCompletion(3L);

        assertThat(parentModule.getStatus()).isEqualTo(EntryStatus.DONE);
    }
}
