package com.compass.app.events;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

/**
 * Keeps {@code system_events} a lightweight recent-signal view, not an ever-growing log dump
 * (Phase 5). Prunes on a schedule two ways: drop anything older than a retention window, and
 * never keep more than a row cap. Runs shortly after startup and then periodically.
 */
@Service
public class EventRetentionService {

    private static final Logger log = LoggerFactory.getLogger(EventRetentionService.class);

    private final SystemEventRepository repository;
    private final int retentionDays;
    private final int maxRows;

    public EventRetentionService(SystemEventRepository repository,
                                 @Value("${compass.events.retention-days:30}") int retentionDays,
                                 @Value("${compass.events.max-rows:1000}") int maxRows) {
        this.repository = repository;
        this.retentionDays = retentionDays;
        this.maxRows = maxRows;
    }

    @Scheduled(
            initialDelayString = "${compass.events.prune-initial-delay-ms:60000}",
            fixedDelayString = "${compass.events.prune-interval-ms:3600000}")
    @Transactional
    public void prune() {
        int byAge = repository.deleteOlderThan(Instant.now().minus(retentionDays, ChronoUnit.DAYS));
        int byCount = repository.deleteBeyondNewest(maxRows);
        if (byAge + byCount > 0) {
            log.info("Pruned {} old and {} overflow system events (keep {}d / {} rows).",
                    byAge, byCount, retentionDays, maxRows);
        }
    }
}
