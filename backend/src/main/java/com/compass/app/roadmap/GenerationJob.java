package com.compass.app.roadmap;

import com.compass.app.roadmap.dto.GenerateRoadmapResponse;

import java.time.Instant;

/**
 * The state of one roadmap-generation job (Phase 18) — created immediately by
 * {@link GenerationJobService#start}, updated in place by the background worker as it moves
 * through {@link GenerationStage}s, and read (unmodified) by the polling endpoint. All mutable
 * fields are volatile; the worker always writes the payload ({@code result}/{@code error}/
 * {@code finishedAt}) before the {@code status} field that signals completion, so a reader that
 * observes {@code status == DONE} is guaranteed (via the volatile happens-before edge) to see the
 * result that goes with it.
 */
final class GenerationJob {

    enum Status { PENDING, DONE, FAILED }

    private final String id;
    private final Instant createdAt = Instant.now();
    private volatile GenerationStage stage;
    private volatile Status status = Status.PENDING;
    private volatile GenerateRoadmapResponse result;
    private volatile String error;
    private volatile Instant finishedAt;

    GenerationJob(String id) {
        this.id = id;
    }

    String id() {
        return id;
    }

    Instant createdAt() {
        return createdAt;
    }

    Instant finishedAt() {
        return finishedAt;
    }

    Status status() {
        return status;
    }

    GenerationStage stage() {
        return stage;
    }

    GenerateRoadmapResponse result() {
        return result;
    }

    String error() {
        return error;
    }

    void setStage(GenerationStage stage) {
        this.stage = stage;
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
}
