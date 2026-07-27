package com.compass.app.roadmap;

import com.compass.app.events.EventService;
import com.compass.app.roadmap.dto.ModulePrefetchStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Drafts unexpanded modules in the background so they're often already ready by the time the
 * founder opens one — instead of the on-demand "Expand this module" click blocking on a single
 * AI call that can take up to ~75s on the free-tier tertiary provider. Triggered by
 * {@link RoadmapController} whenever an unexpanded module appears or changes (roadmap created
 * from an accepted outline, a module inserted/regenerated/replanned) — every module drafts
 * concurrently (capped, same reasoning as the existing founder-triggered batch-expand), each as
 * its own real AI call.
 *
 * <p>Deliberately calls {@link RoadmapService#expandModule} on the injected bean reference (a
 * genuine cross-bean call), not a self-invocation from within {@code RoadmapService} itself —
 * self-invocation would silently skip that method's {@code @Transactional} proxy, same pitfall
 * {@link GenerationWorker} is already structured to avoid for the top-level generate job.
 *
 * <p>In-memory, not a DB table — same reasoning as {@link GenerationJobService}: single-user, no
 * auth, a lost job on restart just means the founder sees "Expand this module" again.
 */
@Service
public class ModulePrefetchService {

    private static final Duration RETENTION = Duration.ofMinutes(45);
    // Same concurrency cap as the founder-triggered batch expand (Phase 19) — a separate pool
    // (not shared with RoadmapService's own executor) so this file doesn't need a hard dependency
    // in the other direction; the frontend closes the one real collision (offering batch-select
    // on a module already being background-drafted) by hiding that checkbox once a job exists.
    private static final int MAX_CONCURRENT = 4;

    private final RoadmapService roadmapService;
    private final EventService events;
    private final ExecutorService executor = Executors.newFixedThreadPool(MAX_CONCURRENT);
    private final Map<Long, ModulePrefetchJob> jobs = new ConcurrentHashMap<>();

    public ModulePrefetchService(RoadmapService roadmapService, EventService events) {
        this.roadmapService = roadmapService;
        this.events = events;
    }

    /** Start background drafting for these modules, skipping any already tracked for this id. */
    public void prefetchAll(Long roadmapId, List<Long> moduleIds) {
        for (Long moduleId : moduleIds) {
            ModulePrefetchJob job = new ModulePrefetchJob(moduleId, roadmapId);
            if (jobs.putIfAbsent(moduleId, job) != null) {
                continue;
            }
            CompletableFuture
                    .supplyAsync(() -> roadmapService.expandModule(roadmapId, moduleId), executor)
                    .whenComplete((result, ex) -> {
                        if (ex != null) {
                            events.aiWarning("provider_error",
                                    "Background module prefetch failed: " + rootMessage(ex), null);
                            job.fail(friendlyMessage(ex));
                        } else {
                            job.complete(result);
                        }
                    });
        }
    }

    /**
     * Drop a tracked job — the module's scope changed (regenerate/replan) or it was just
     * accepted, so any in-flight/finished draft under the old key is no longer relevant.
     */
    public void invalidate(Long moduleId) {
        jobs.remove(moduleId);
    }

    /** Every tracked job for this roadmap, for the frontend to poll instead of blocking. */
    public List<ModulePrefetchStatus> statusFor(Long roadmapId) {
        return jobs.values().stream()
                .filter(j -> j.roadmapId().equals(roadmapId))
                .map(ModulePrefetchJob::toResponse)
                .toList();
    }

    @Scheduled(fixedRate = 60_000)
    void sweep() {
        Instant cutoff = Instant.now().minus(RETENTION);
        jobs.values().removeIf(j -> j.finishedAt() != null && j.finishedAt().isBefore(cutoff));
    }

    /**
     * Self-heals a roadmap whose generation never completed (V3-3.5) — the actual gap
     * {@code GenerationJobService}'s in-memory job map correctly leaves alone. A server restart
     * mid-expansion doesn't lose a job, it loses the tracking *of* one: the modules it already
     * persisted with no steps yet are real rows in the database, but {@link #jobs} — also
     * in-memory — has no record of them anymore, so nothing ever prompts a retry and they sit
     * unbuilt until the founder happens to open one by hand.
     *
     * <p>Same shape as {@link SkeletonRetryService}'s periodic re-attempt of a different failure
     * mode (a skeleton that never got upgraded) — extended here to plain unexpanded modules
     * rather than a new mechanism. {@link #prefetchAll} already no-ops on a module id it's
     * already tracking, so a module currently drafting or recently failed (within
     * {@value #RETENTION}) is left alone rather than doubled up.
     */
    @Scheduled(fixedRate = 600_000)
    void selfHealSweep() {
        for (var roadmap : roadmapService.listRoadmaps()) {
            List<Long> unexpanded = roadmapService.unexpandedModuleIds(roadmap.getId());
            if (!unexpanded.isEmpty()) {
                prefetchAll(roadmap.getId(), unexpanded);
            }
        }
    }

    private static String friendlyMessage(Throwable ex) {
        Throwable cause = unwrap(ex);
        if (cause instanceof IllegalArgumentException || cause instanceof IllegalStateException) {
            return cause.getMessage();
        }
        return "Couldn't draft this module in the background — expand it yourself.";
    }

    private static String rootMessage(Throwable ex) {
        return unwrap(ex).getMessage();
    }

    private static Throwable unwrap(Throwable ex) {
        return ex instanceof CompletionException && ex.getCause() != null ? ex.getCause() : ex;
    }
}
