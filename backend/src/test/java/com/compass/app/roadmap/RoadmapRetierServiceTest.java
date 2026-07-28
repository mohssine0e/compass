package com.compass.app.roadmap;

import com.compass.app.ai.AiVoiceService;
import com.compass.app.ai.RoadmapAiService;
import com.compass.app.entry.Entry;
import com.compass.app.entry.EntryRepository;
import com.compass.app.entry.EntryService;
import com.compass.app.entry.EntryStatus;
import com.compass.app.entry.EntryType;
import com.compass.app.events.EventService;
import com.compass.app.roadmap.dto.ApplyReTierProposalRequest;
import com.compass.app.roadmap.dto.ReTierRequest;
import com.compass.app.roadmap.dto.ReTierResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Direct, focused coverage of {@link RoadmapRetierService} (V3-4.1's second extracted seam from
 * the former {@code RoadmapService} god-file — see {@code TASKS_v3.md}), retargeting the same
 * re-tier assertions {@link RoadmapServiceBehaviorTest} already makes through {@code
 * RoadmapService}'s still-delegating facade. Both layers of coverage are intentional: the facade
 * tests prove the move didn't change {@code RoadmapService}'s public contract; these prove the
 * extracted class's own behavior directly.
 */
class RoadmapRetierServiceTest {

    private EntryRepository repository;
    private RoadmapAiService roadmapAi;
    private EntryService entryService;
    private AiVoiceService aiVoice;
    private EventService events;
    private RoadmapRetierService service;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        repository = mock(EntryRepository.class);
        roadmapAi = mock(RoadmapAiService.class);
        entryService = mock(EntryService.class);
        aiVoice = mock(AiVoiceService.class);
        events = mock(EventService.class);
        service = new RoadmapRetierService(repository, new RoadmapQueryService(repository),
                roadmapAi, entryService, aiVoice, events);

        // IDENTITY-generation save contract (see Entry's @GeneratedValue): applyRegroup saves a
        // brand-new module Entry and then immediately reparents steps under module.getId() — a
        // pure identity-passthrough stub would leave that id null instead of reproducing what
        // Postgres actually does on INSERT.
        when(repository.save(any(Entry.class))).thenAnswer(inv -> {
            Entry e = inv.getArgument(0);
            if (e.getId() == null) {
                setId(e, ++nextId);
            }
            return e;
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

    // ── reTier ──────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("reTier to TASK archives the roadmap and creates a task entry")
    void reTierToTaskArchivesAndCreatesTask() {
        Entry r = roadmap(1, "R");
        when(repository.findById(1L)).thenReturn(Optional.of(r));
        when(repository.findByParentIdOrderByOrderIndexAsc(1L)).thenReturn(List.of());
        Entry task = entryWithId(99, EntryType.TASK, EntryStatus.CAPTURED, null, null, null);
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
}
