package com.compass.app.roadmap;

import com.compass.app.ai.RoadmapAiService;
import com.compass.app.entry.EntryRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Periodically retries modules stuck with skeleton (titles-only) steps (Phase 19) — the
 * emergency fallback used when the whole heavy AI chain failed a module expansion. A provider
 * quota that was exhausted when the skeleton was created may have recovered since, so this
 * quietly re-attempts the full expansion and replaces the skeleton in place on success, per
 * {@link RoadmapService#retrySkeletonModule}.
 */
@Component
class SkeletonRetryService {

    private static final Logger log = LoggerFactory.getLogger(SkeletonRetryService.class);

    private final EntryRepository repository;
    private final RoadmapAiService roadmapAi;
    private final RoadmapService roadmapService;

    SkeletonRetryService(EntryRepository repository, RoadmapAiService roadmapAi, RoadmapService roadmapService) {
        this.repository = repository;
        this.roadmapAi = roadmapAi;
        this.roadmapService = roadmapService;
    }

    // Frequent enough to notice a recovered provider within a work session, infrequent enough
    // not to hammer quota retrying modules that are still down.
    @Scheduled(fixedRate = 600_000)
    void sweep() {
        if (!roadmapAi.isAvailable()) {
            return;
        }
        List<Long> moduleIds = repository.findModuleIdsWithSkeletonSteps();
        for (Long moduleId : moduleIds) {
            try {
                if (roadmapService.retrySkeletonModule(moduleId)) {
                    log.info("Filled in full detail for previously-skeleton module {}.", moduleId);
                }
            } catch (RuntimeException ex) {
                log.warn("Skeleton retry failed for module {}: {}", moduleId, ex.getMessage());
            }
        }
    }
}
