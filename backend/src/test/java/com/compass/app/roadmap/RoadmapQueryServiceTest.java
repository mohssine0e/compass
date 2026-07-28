package com.compass.app.roadmap;

import com.compass.app.entry.Entry;
import com.compass.app.entry.EntryRepository;
import com.compass.app.entry.EntryStatus;
import com.compass.app.entry.EntryType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Direct, focused coverage of {@link RoadmapQueryService} (V3-4.1's first extracted seam from the
 * former {@code RoadmapService} god-file — see {@code TASKS_v3.md}) — the same assertions {@link
 * RoadmapServiceBehaviorTest} and {@link RoadmapServicePureLogicTest} already make through
 * {@code RoadmapService}'s still-delegating facade methods, retargeted at the extracted class
 * itself so future collaborators that depend on {@code RoadmapQueryService} directly (not through
 * the facade) have their own pin. Both layers of coverage are intentional, not redundant: the
 * facade tests prove the move didn't change {@code RoadmapService}'s public contract; these prove
 * the extracted class's own behavior directly.
 */
class RoadmapQueryServiceTest {

    private EntryRepository repository;
    private RoadmapQueryService service;

    @BeforeEach
    void setUp() {
        repository = mock(EntryRepository.class);
        service = new RoadmapQueryService(repository);
    }

    private static Entry entryWithId(long id, EntryType type, EntryStatus status, Long parentId,
                                     Map<String, Object> content) {
        Entry e = new Entry();
        try {
            java.lang.reflect.Field idField = Entry.class.getDeclaredField("id");
            idField.setAccessible(true);
            idField.set(e, id);
        } catch (ReflectiveOperationException ex) {
            throw new RuntimeException(ex);
        }
        e.setType(type);
        e.setStatus(status);
        e.setParentId(parentId);
        e.setContent(content == null ? new HashMap<>() : content);
        return e;
    }

    // ── getRoadmap ──────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("getRoadmap 404s on a missing id, and on an id that isn't a ROADMAP")
    void getRoadmapValidatesTypeAndExistence() {
        when(repository.findById(1L)).thenReturn(Optional.empty());
        when(repository.findById(2L)).thenReturn(Optional.of(
                entryWithId(2, EntryType.ROADMAP_STEP, EntryStatus.CAPTURED, null, null)));

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.getRoadmap(1L))
                .isInstanceOf(java.util.NoSuchElementException.class);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.getRoadmap(2L))
                .isInstanceOf(java.util.NoSuchElementException.class);
    }

    @Test
    @DisplayName("getRoadmap returns the entry when it exists and is a ROADMAP")
    void getRoadmapReturnsRoadmap() {
        Entry roadmap = entryWithId(1, EntryType.ROADMAP, EntryStatus.IN_MOTION, null, null);
        when(repository.findById(1L)).thenReturn(Optional.of(roadmap));

        assertThat(service.getRoadmap(1L)).isSameAs(roadmap);
    }

    // ── requireModule ───────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("requireModule 404s when the module doesn't belong to the given roadmap")
    void requireModuleRejectsWrongRoadmap() {
        Entry wrongRoadmap = entryWithId(2, EntryType.ROADMAP, EntryStatus.IN_MOTION, 999L, null);
        when(repository.findById(2L)).thenReturn(Optional.of(wrongRoadmap));

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.requireModule(1L, 2L))
                .isInstanceOf(java.util.NoSuchElementException.class);
    }

    // ── stepsOf / leafStepsOf ───────────────────────────────────────────────────────────

    @Test
    @DisplayName("stepsOf returns the repository's ordered children as-is")
    void stepsOfDelegatesToRepository() {
        List<Entry> steps = List.of(entryWithId(2, EntryType.ROADMAP_STEP, EntryStatus.CAPTURED, 1L, null));
        when(repository.findByParentIdOrderByOrderIndexAsc(1L)).thenReturn(steps);

        assertThat(service.stepsOf(1L)).isEqualTo(steps);
    }

    @Test
    @DisplayName("leafStepsOf returns only ROADMAP_STEP descendants that are nobody's parent")
    void leafStepsOfExcludesContainers() {
        Entry module = entryWithId(10, EntryType.ROADMAP, EntryStatus.IN_MOTION, 1L, null);
        Entry container = entryWithId(11, EntryType.ROADMAP_STEP, EntryStatus.CAPTURED, 10L, null);
        Entry leaf = entryWithId(12, EntryType.ROADMAP_STEP, EntryStatus.CAPTURED, 11L, null);
        Entry directLeaf = entryWithId(13, EntryType.ROADMAP_STEP, EntryStatus.CAPTURED, 10L, null);
        when(repository.findDescendants(1L)).thenReturn(List.of(module, container, leaf, directLeaf));

        assertThat(service.leafStepsOf(1L)).containsExactly(leaf, directLeaf);
    }

    // ── depthOf / MAX_STEP_DEPTH / isAtMaxStepDepth ────────────────────────────────────

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
        when(repository.findById(99L)).thenReturn(Optional.of(step));
        when(repository.findAncestors(Mockito.any())).thenReturn(List.of(new Entry()));

        assertThat(service.isAtMaxStepDepth(99L)).isFalse();
    }

    @Test
    @DisplayName("a step exactly at MAX_STEP_DEPTH is at max, not one past it")
    void exactlyAtCapIsAtMax() {
        Entry step = new Entry();
        step.setParentId(1L);
        when(repository.findById(99L)).thenReturn(Optional.of(step));
        when(repository.findAncestors(Mockito.any()))
                .thenReturn(Collections.nCopies(RoadmapQueryService.MAX_STEP_DEPTH, new Entry()));

        assertThat(service.isAtMaxStepDepth(99L)).isTrue();
    }

    @Test
    @DisplayName("a missing entry is not treated as being at the cap")
    void missingEntryIsNotAtMax() {
        when(repository.findById(404L)).thenReturn(Optional.empty());

        assertThat(service.isAtMaxStepDepth(404L)).isFalse();
    }

    // ── domainOf ────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("domainOf walks to the root and reads its stored assessment domain")
    void domainOfWalksToRoot() {
        Entry leaf = entryWithId(5, EntryType.ROADMAP_STEP, EntryStatus.CAPTURED, 4L, null);
        Map<String, Object> rootContent = new HashMap<>();
        rootContent.put("assessment", Map.of("domain", "cybersecurity"));
        Entry root = entryWithId(1, EntryType.ROADMAP, EntryStatus.IN_MOTION, null, rootContent);
        Entry module = entryWithId(4, EntryType.ROADMAP, EntryStatus.IN_MOTION, 1L, null);
        when(repository.findById(5L)).thenReturn(Optional.of(leaf));
        when(repository.findAncestors(5L)).thenReturn(List.of(module, root));

        assertThat(service.domainOf(5L)).isEqualTo("cybersecurity");
    }

    @Test
    @DisplayName("domainOf returns null for a missing node, or a root with no stored assessment")
    void domainOfDegradesToNull() {
        when(repository.findById(404L)).thenReturn(Optional.empty());
        assertThat(service.domainOf(404L)).isNull();

        Entry noAssessment = entryWithId(1, EntryType.ROADMAP, EntryStatus.IN_MOTION, null, null);
        when(repository.findById(1L)).thenReturn(Optional.of(noAssessment));
        when(repository.findAncestors(1L)).thenReturn(List.of());
        assertThat(service.domainOf(1L)).isNull();
    }

    // ── listRoadmaps / listArchivedRoadmaps ────────────────────────────────────────────

    @Test
    @DisplayName("listRoadmaps returns only top-level (no parent), non-archived roadmaps")
    void listRoadmapsFiltersChildrenAndArchived() {
        Entry top = entryWithId(1, EntryType.ROADMAP, EntryStatus.IN_MOTION, null, null);
        Entry child = entryWithId(2, EntryType.ROADMAP, EntryStatus.IN_MOTION, 1L, null);
        Entry archived = entryWithId(3, EntryType.ROADMAP, EntryStatus.ARCHIVED, null, null);
        when(repository.findByTypeOrderByCreatedAtDesc(EntryType.ROADMAP))
                .thenReturn(List.of(top, child, archived));

        assertThat(service.listRoadmaps()).containsExactly(top);
    }

    @Test
    @DisplayName("listArchivedRoadmaps returns only top-level archived roadmaps")
    void listArchivedRoadmapsFiltersToArchivedTopLevel() {
        Entry active = entryWithId(1, EntryType.ROADMAP, EntryStatus.IN_MOTION, null, null);
        Entry archived = entryWithId(2, EntryType.ROADMAP, EntryStatus.ARCHIVED, null, null);
        Entry archivedChild = entryWithId(3, EntryType.ROADMAP, EntryStatus.ARCHIVED, 2L, null);
        when(repository.findByTypeOrderByCreatedAtDesc(EntryType.ROADMAP))
                .thenReturn(List.of(active, archived, archivedChild));

        assertThat(service.listArchivedRoadmaps()).containsExactly(archived);
    }

    // ── unexpandedModuleIds ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("unexpandedModuleIds returns only modules with zero children")
    void unexpandedModuleIdsFindsChildlessModules() {
        Entry expanded = entryWithId(2, EntryType.ROADMAP, EntryStatus.IN_MOTION, 1L, null);
        Entry unexpanded = entryWithId(3, EntryType.ROADMAP, EntryStatus.IN_MOTION, 1L, null);
        when(repository.findByParentIdOrderByOrderIndexAsc(1L)).thenReturn(List.of(expanded, unexpanded));
        when(repository.findByParentIdOrderByOrderIndexAsc(2L))
                .thenReturn(List.of(entryWithId(4, EntryType.ROADMAP_STEP, EntryStatus.CAPTURED, 2L, null)));
        when(repository.findByParentIdOrderByOrderIndexAsc(3L)).thenReturn(List.of());

        assertThat(service.unexpandedModuleIds(1L)).containsExactly(3L);
    }
}
