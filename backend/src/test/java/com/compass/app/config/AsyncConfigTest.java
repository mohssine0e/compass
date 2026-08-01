package com.compass.app.config;

import com.compass.app.events.EventService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * V4-6.3 (2026-07-30 user audit): neither executor had a queue capacity set, so
 * {@code ThreadPoolTaskExecutor} defaulted to an unbounded queue — the pool-size limits existed
 * on paper only, since tasks would just pile up forever under sustained load instead of the pool
 * ever actually backpressuring. Pins down that both pools are genuinely bounded now, and that
 * overflow is logged rather than silently dropped or thrown onto the submitting thread.
 *
 * <p>No Spring context — {@code AsyncConfig} is a plain class with package-visible bean methods,
 * constructed and called directly, same "no Spring context, no network" spirit as this
 * codebase's other pure unit tests (see {@code TASKS_v3.md} V3-1.1).
 */
class AsyncConfigTest {

    private final EventService events = mock(EventService.class);
    private final AsyncConfig config = new AsyncConfig(events);
    private ExecutorService expansionExecutor;
    private ThreadPoolTaskExecutor voiceExecutor;

    @AfterEach
    void tearDown() {
        if (expansionExecutor != null) expansionExecutor.shutdownNow();
        if (voiceExecutor != null) voiceExecutor.shutdown();
    }

    @Test
    @DisplayName("expansionExecutor's queue is bounded, not the ThreadPoolTaskExecutor default of unbounded")
    void expansionExecutorQueueIsBounded() {
        expansionExecutor = config.expansionExecutor();

        var pool = (java.util.concurrent.ThreadPoolExecutor) expansionExecutor;
        assertThat(pool.getQueue().remainingCapacity()).isLessThan(Integer.MAX_VALUE);
        assertThat(pool.getMaximumPoolSize()).isEqualTo(4);
    }

    @Test
    @DisplayName("voiceExecutor's queue is bounded too")
    void voiceExecutorQueueIsBounded() {
        voiceExecutor = config.voiceExecutor();

        var pool = voiceExecutor.getThreadPoolExecutor();
        assertThat(pool.getQueue().remainingCapacity()).isLessThan(Integer.MAX_VALUE);
        assertThat(pool.getMaximumPoolSize()).isEqualTo(2);
    }

    @Test
    @DisplayName("a task that overflows both the pool and its queue is logged as a system event, not silently dropped")
    void overflowIsLoggedNotDropped() throws InterruptedException {
        voiceExecutor = config.voiceExecutor();
        var pool = voiceExecutor.getThreadPoolExecutor();
        // Occupy both of voiceExecutor's threads (core == max == 2, so no more will ever spin
        // up) so every later submission has nowhere to run except the queue.
        CountDownLatch block = new CountDownLatch(1);
        for (int i = 0; i < 2; i++) {
            pool.execute(() -> {
                try {
                    block.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
            });
        }
        // Fill the bounded queue completely (capacity 50 — see AsyncConfig.QUEUE_CAPACITY).
        for (int i = 0; i < 50; i++) {
            pool.getQueue().put(() -> { });
        }

        // Nowhere left to go: both threads are busy and the queue is full — exactly the
        // overflow case the rejection handler exists for.
        pool.execute(() -> { });

        verify(events).systemError(eq("task_rejected"), contains("voice"), any());
        block.countDown();
    }
}
