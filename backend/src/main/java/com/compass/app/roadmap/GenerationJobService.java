package com.compass.app.roadmap;

import com.compass.app.roadmap.dto.GenerateRoadmapRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Runs a roadmap-drafting request in the background and lets the caller poll its progress
 * (Phase 18) — the free-tier tertiary AI provider can take up to a minute, and a plain blocking
 * request left the founder staring at a frozen button with no sign of whether it was working or
 * stuck. A job survives independently of any one HTTP connection, so it can be started on one
 * device and checked from another (e.g. started on a laptop, polled from a phone).
 *
 * <p>Deliberately an in-memory {@link ConcurrentHashMap}, not a database table: this is a
 * single-user app with no auth (CLAUDE.md), a job is only ever a few minutes old, and losing one
 * on a server restart just means "generate again" — not worth migration/entity ceremony for.
 */
@Service
public class GenerationJobService {

    // A finished job is kept a while so a slow poller doesn't miss it, then swept — otherwise a
    // long-running server would slowly accumulate every past job's result forever.
    private static final Duration RETENTION = Duration.ofMinutes(10);

    private final Map<String, GenerationJob> jobs = new ConcurrentHashMap<>();
    private final GenerationWorker worker;

    public GenerationJobService(GenerationWorker worker) {
        this.worker = worker;
    }

    /** Start a job for this request; returns its id immediately without waiting for it to finish. */
    public String start(GenerateRoadmapRequest req) {
        String id = UUID.randomUUID().toString();
        GenerationJob job = new GenerationJob(id);
        jobs.put(id, job);
        worker.run(job, req);
        return id;
    }

    /** The current state of a job, or throws if it never existed or has since been swept. */
    public GenerationJob get(String jobId) {
        GenerationJob job = jobs.get(jobId);
        if (job == null) {
            throw new NoSuchElementException("No generation job " + jobId);
        }
        return job;
    }

    /** Drop finished jobs old enough that no reasonable poller is still waiting on them. */
    @Scheduled(fixedRate = 60_000)
    void sweep() {
        Instant cutoff = Instant.now().minus(RETENTION);
        jobs.values().removeIf(j -> j.finishedAt() != null && j.finishedAt().isBefore(cutoff));
    }
}
