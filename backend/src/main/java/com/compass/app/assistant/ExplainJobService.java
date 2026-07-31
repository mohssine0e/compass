package com.compass.app.assistant;

import com.compass.app.assistant.dto.ExplainRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Runs in-content "explain selected text" help in the background and lets the caller poll its
 * progress (V3-10) — same reasoning as {@code GenerationJobService}: the answer is a real FAST-tier
 * AI call that can iterate through several providers before one answers, and the founder deserves
 * visible progress ("Thinking…") rather than either an unbounded hang or a hard timeout that
 * throws away work a slower provider was still doing.
 *
 * <p>In-memory, not a DB table — a lost job on restart just means the founder re-asks.
 */
@Service
public class ExplainJobService {

    private static final Duration RETENTION = Duration.ofMinutes(10);

    private final Map<String, ExplainJob> jobs = new ConcurrentHashMap<>();
    private final ExplainWorker worker;

    public ExplainJobService(ExplainWorker worker) {
        this.worker = worker;
    }

    /** Start a job for this request; returns its id immediately without waiting for it to finish. */
    public String start(ExplainRequest req) {
        String id = UUID.randomUUID().toString();
        ExplainJob job = new ExplainJob(id);
        jobs.put(id, job);
        worker.run(job, req);
        return id;
    }

    /** The current state of a job, or throws if it never existed or has since been swept. */
    public ExplainJob get(String jobId) {
        ExplainJob job = jobs.get(jobId);
        if (job == null) {
            throw new NoSuchElementException("No explain job " + jobId);
        }
        return job;
    }

    @Scheduled(fixedRate = 60_000)
    void sweep() {
        Instant cutoff = Instant.now().minus(RETENTION);
        jobs.values().removeIf(j -> j.finishedAt() != null && j.finishedAt().isBefore(cutoff));
    }
}
