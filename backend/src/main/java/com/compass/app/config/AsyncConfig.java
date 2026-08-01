package com.compass.app.config;

import com.compass.app.events.EventService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionHandler;

/**
 * The pool that parallel module expansion runs on.
 *
 * <p>{@code RoadmapService} used to create this itself as a plain field
 * ({@code Executors.newFixedThreadPool(4)}) and never shut it down, so a
 * {@code spring-boot:run} restart could leave threads mid-AI-call — each holding a socket and a
 * transaction — with nothing to stop them. As a bean, Spring owns the lifecycle and drains it on
 * shutdown, and the named threads show up identifiably in a thread dump instead of as
 * {@code pool-3-thread-2}.
 */
@Configuration
class AsyncConfig {

    /**
     * Bounds concurrency for {@code expandModulesBatch} (Phase 19) so a batch of many modules
     * doesn't trip rate limits across the whole provider chain at once. Kept at 4: the reason is
     * provider quota, not local CPU, so it should not scale with core count.
     */
    private static final int MAX_CONCURRENT_EXPANSIONS = 4;

    /**
     * V4-6.3 (2026-07-30 user audit): neither pool had a queue capacity set, so
     * {@code ThreadPoolTaskExecutor} defaulted to an unbounded queue — under sustained load,
     * tasks would just pile up forever instead of the pool ever actually rejecting/backpressuring,
     * making the pool-size limits above purely decorative. Generous rather than tight: the goal is
     * "not literally unbounded" (a defense against a genuine runaway/leak bug), not "tightly sized
     * to today's expected load" — a real batch-expand of every module in a large roadmap, or a
     * burst of capture acknowledgments, should still comfortably queue rather than get rejected.
     */
    private static final int QUEUE_CAPACITY = 50;

    private final EventService events;

    AsyncConfig(EventService events) {
        this.events = events;
    }

    @Bean(destroyMethod = "shutdown")
    ExecutorService expansionExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(MAX_CONCURRENT_EXPANSIONS);
        executor.setMaxPoolSize(MAX_CONCURRENT_EXPANSIONS);
        executor.setQueueCapacity(QUEUE_CAPACITY);
        executor.setThreadNamePrefix("expansion-");
        // A generation in flight is worth finishing: on shutdown, wait rather than abandoning a
        // half-expanded roadmap mid-write.
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(30);
        executor.setRejectedExecutionHandler(loggingRejectionHandler("expansion"));
        executor.initialize();
        return executor.getThreadPoolExecutor();
    }

    /**
     * A separate, small pool for capture/mark-done voice acknowledgments ({@code AiVoiceWorker}).
     * Deliberately not shared with {@link #expansionExecutor()}: a batch module expansion can
     * occupy all 4 of those threads for a while, and an acknowledgment is short and frequent
     * enough that it shouldn't queue behind one.
     */
    @Bean(destroyMethod = "shutdown", name = "voiceExecutor")
    ThreadPoolTaskExecutor voiceExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(2);
        executor.setQueueCapacity(QUEUE_CAPACITY);
        executor.setThreadNamePrefix("voice-");
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(10);
        executor.setRejectedExecutionHandler(loggingRejectionHandler("voice"));
        executor.initialize();
        return executor;
    }

    /**
     * On genuine overflow (queue AND pool both full — the case a bounded queue now actually
     * makes possible), record a brief system event rather than silently dropping the task or
     * throwing an uncaught {@link java.util.concurrent.RejectedExecutionException} on whatever
     * request thread submitted it. No retry queue: CLAUDE.md Section 2 keeps
     * {@code system_events} brief and best-effort, same as every other operational log point in
     * this codebase.
     */
    private RejectedExecutionHandler loggingRejectionHandler(String poolName) {
        return (task, executor) -> events.systemError("task_rejected",
                "The " + poolName + " pool rejected a task — its queue is full ("
                        + executor.getQueue().size() + " already waiting).", null);
    }
}
