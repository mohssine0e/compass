package com.compass.app.roadmap;

import com.compass.app.entry.Entry;
import com.compass.app.events.EventService;
import com.compass.app.roadmap.dto.GenerateRoadmapResponse;
import com.compass.app.roadmap.dto.ModulePrefetchStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * V3-3.5: a module left with zero steps because the server restarted mid-draft has no active
 * {@code ModulePrefetchService} job tracking it anymore — that map is in-memory, same as
 * {@code GenerationJobService}'s. The self-heal sweep is what notices and re-drafts it without
 * the founder having to open the module by hand.
 */
class ModulePrefetchServiceSelfHealTest {

    private static Entry roadmapEntryWithId(long id) {
        Entry e = org.mockito.Mockito.spy(new Entry());
        org.mockito.Mockito.doReturn(id).when(e).getId();
        return e;
    }

    @Test
    @DisplayName("an unexpanded module with no tracked job gets re-drafted")
    void redraftsUntrackedUnexpandedModule() {
        RoadmapService roadmapService = mock(RoadmapService.class);
        EventService events = mock(EventService.class);
        ModulePrefetchService service = new ModulePrefetchService(roadmapService, events);

        Entry roadmap = roadmapEntryWithId(100L);
        when(roadmapService.listRoadmaps()).thenReturn(List.of(roadmap));
        when(roadmapService.unexpandedModuleIds(100L)).thenReturn(List.of(42L));
        when(roadmapService.expandModule(100L, 42L))
                .thenReturn(GenerateRoadmapResponse.routedToTask(1L, "n/a", null));

        service.selfHealSweep();

        awaitDone(service, 100L, 42L);
        verify(roadmapService, times(1)).expandModule(100L, 42L);
    }

    @Test
    @DisplayName("a module already tracked (drafting or recently failed) is not re-submitted")
    void doesNotDoubleSubmitAnAlreadyTrackedModule() {
        RoadmapService roadmapService = mock(RoadmapService.class);
        EventService events = mock(EventService.class);
        ModulePrefetchService service = new ModulePrefetchService(roadmapService, events);

        Entry roadmap = roadmapEntryWithId(100L);
        when(roadmapService.listRoadmaps()).thenReturn(List.of(roadmap));
        when(roadmapService.unexpandedModuleIds(100L)).thenReturn(List.of(42L));
        AtomicInteger calls = new AtomicInteger();
        when(roadmapService.expandModule(100L, 42L)).thenAnswer(inv -> {
            calls.incrementAndGet();
            return GenerateRoadmapResponse.routedToTask(1L, "n/a", null);
        });

        // First sweep starts tracking module 42.
        service.selfHealSweep();
        // A second sweep before the first has necessarily finished must not double-submit —
        // prefetchAll's putIfAbsent is what's actually under test here.
        service.selfHealSweep();

        awaitDone(service, 100L, 42L);
        assertThat(calls.get()).isEqualTo(1);
        verify(roadmapService, times(1)).expandModule(anyLong(), eq(42L));
    }

    @Test
    @DisplayName("a roadmap with nothing unexpanded triggers no drafting at all")
    void fullyExpandedRoadmapTriggersNothing() {
        RoadmapService roadmapService = mock(RoadmapService.class);
        EventService events = mock(EventService.class);
        ModulePrefetchService service = new ModulePrefetchService(roadmapService, events);

        Entry roadmap = roadmapEntryWithId(100L);
        when(roadmapService.listRoadmaps()).thenReturn(List.of(roadmap));
        when(roadmapService.unexpandedModuleIds(100L)).thenReturn(List.of());

        service.selfHealSweep();

        verify(roadmapService, never()).expandModule(anyLong(), anyLong());
        assertThat(service.statusFor(100L)).isEmpty();
    }

    private static String statusOf(ModulePrefetchService service, Long roadmapId, Long moduleId) {
        return service.statusFor(roadmapId).stream()
                .filter(s -> s.moduleId().equals(moduleId))
                .findFirst()
                .map(ModulePrefetchStatus::status)
                .orElse("MISSING");
    }

    /** No Awaitility in this project's dependency set — a plain poll loop does the same job here. */
    private static void awaitDone(ModulePrefetchService service, Long roadmapId, Long moduleId) {
        long deadline = System.currentTimeMillis() + 2000;
        while (System.currentTimeMillis() < deadline) {
            if ("DONE".equals(statusOf(service, roadmapId, moduleId))) {
                return;
            }
            try {
                Thread.sleep(20);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw new RuntimeException(ex);
            }
        }
        throw new AssertionError("Module " + moduleId + " never reached DONE within 2s; last status: "
                + statusOf(service, roadmapId, moduleId));
    }
}
