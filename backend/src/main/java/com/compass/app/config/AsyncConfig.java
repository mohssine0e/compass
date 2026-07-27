package com.compass.app.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.ExecutorService;

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

    @Bean(destroyMethod = "shutdown")
    ExecutorService expansionExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(MAX_CONCURRENT_EXPANSIONS);
        executor.setMaxPoolSize(MAX_CONCURRENT_EXPANSIONS);
        executor.setThreadNamePrefix("expansion-");
        // A generation in flight is worth finishing: on shutdown, wait rather than abandoning a
        // half-expanded roadmap mid-write.
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(30);
        executor.initialize();
        return executor.getThreadPoolExecutor();
    }
}
