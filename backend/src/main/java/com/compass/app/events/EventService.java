package com.compass.app.events;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

/**
 * The one place code records a {@link SystemEvent}. Deliberately best-effort: recording an
 * event must never throw into the caller — an operational log failing is not worth breaking a
 * capture, a resurfacing prompt, or an AI fallback that already handled its own error. Writes
 * go through {@link EventWriter} in their own transaction so they survive even when the
 * surrounding request rolls back.
 */
@Service
public class EventService {

    /** Hard cap so an event stays one sentence, not a paragraph (CLAUDE.md Section 2). */
    static final int MAX_MESSAGE = 280;

    /** Default and ceiling for how many recent events the admin view pulls at once. */
    static final int DEFAULT_LIMIT = 100;
    static final int MAX_LIMIT = 500;

    private static final Logger log = LoggerFactory.getLogger(EventService.class);

    private final EventWriter writer;
    private final SystemEventRepository repository;

    public EventService(EventWriter writer, SystemEventRepository repository) {
        this.writer = writer;
        this.repository = repository;
    }

    /**
     * Recent events, newest first, optionally filtered by source and/or severity (null = any).
     * {@code limit} is clamped to a sane range so the admin view stays a recent-signal view.
     */
    @Transactional(readOnly = true)
    public List<SystemEvent> recent(EventSource source, EventSeverity severity, Integer limit) {
        int capped = limit == null ? DEFAULT_LIMIT : Math.max(1, Math.min(limit, MAX_LIMIT));
        return repository.findRecent(source, severity, PageRequest.of(0, capped));
    }

    /** Record any event. Never throws — a failure here is logged and swallowed. */
    public void record(EventSource source, String category, String message,
                       EventSeverity severity, Map<String, Object> context) {
        try {
            SystemEvent event = new SystemEvent();
            event.setSource(source);
            event.setCategory(category);
            event.setMessage(truncate(message));
            event.setSeverity(severity);
            event.setContext(context);
            writer.write(event);
        } catch (Exception ex) {
            log.warn("Could not record system event ({}/{}): {}", source, category, ex.getMessage());
        }
    }

    /** An AI provider degraded (failover or a plain fallback) — a warning, not a hard error. */
    public void aiWarning(String category, String message, Map<String, Object> context) {
        record(EventSource.AI_PROVIDER, category, message, EventSeverity.WARNING, context);
    }

    /** A system-side failure (DB error, unexpected null in a critical path). */
    public void systemError(String category, String message, Map<String, Object> context) {
        record(EventSource.SYSTEM, category, message, EventSeverity.ERROR, context);
    }

    private static String truncate(String message) {
        if (message == null) {
            return "";
        }
        String trimmed = message.strip();
        return trimmed.length() <= MAX_MESSAGE ? trimmed : trimmed.substring(0, MAX_MESSAGE - 1).strip() + "…";
    }
}
