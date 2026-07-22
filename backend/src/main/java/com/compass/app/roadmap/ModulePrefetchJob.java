package com.compass.app.roadmap;

import com.compass.app.roadmap.dto.GenerateRoadmapResponse;
import com.compass.app.roadmap.dto.ModulePrefetchStatus;

import java.time.Instant;

/**
 * The state of one module's background drafting attempt, kicked off automatically whenever an
 * unexpanded module appears (roadmap created from an outline, a module inserted/regenerated/
 * replanned) — see {@link ModulePrefetchService}. Same volatile-fields-before-status-flip shape
 * as {@link GenerationJob}, for the same reason: a reader that observes a non-PENDING status is
 * guaranteed to see the result/error that goes with it.
 */
final class ModulePrefetchJob {

    enum Status { PENDING, DONE, FAILED }

    private final Long moduleId;
    private final Long roadmapId;
    private volatile Status status = Status.PENDING;
    private volatile GenerateRoadmapResponse result;
    private volatile String error;
    private volatile Instant finishedAt;

    ModulePrefetchJob(Long moduleId, Long roadmapId) {
        this.moduleId = moduleId;
        this.roadmapId = roadmapId;
    }

    Long moduleId() {
        return moduleId;
    }

    Long roadmapId() {
        return roadmapId;
    }

    Instant finishedAt() {
        return finishedAt;
    }

    void complete(GenerateRoadmapResponse result) {
        this.result = result;
        this.finishedAt = Instant.now();
        this.status = Status.DONE;
    }

    void fail(String error) {
        this.error = error;
        this.finishedAt = Instant.now();
        this.status = Status.FAILED;
    }

    ModulePrefetchStatus toResponse() {
        return new ModulePrefetchStatus(moduleId, status.name(), result, error);
    }
}
