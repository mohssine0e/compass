package com.compass.app.assistant;

import java.time.Instant;

/**
 * The state of one in-content "explain" request (V3-10) — same volatile-fields-before-status-flip
 * shape as {@code GenerationJob}/{@code ModulePrefetchJob}, for the same reason: a reader that
 * observes a non-PENDING status is guaranteed to see the result/error that goes with it.
 */
final class ExplainJob {

    enum Status { PENDING, DONE, FAILED }

    private final String id;
    private final Instant createdAt = Instant.now();
    private volatile Status status = Status.PENDING;
    private volatile String result;
    private volatile String error;
    private volatile Instant finishedAt;

    ExplainJob(String id) {
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

    String result() {
        return result;
    }

    String error() {
        return error;
    }

    void complete(String result) {
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
