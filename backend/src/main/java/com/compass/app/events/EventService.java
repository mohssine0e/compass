package com.compass.app.events;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

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

    private static final Logger log = LoggerFactory.getLogger(EventService.class);

    private final EventWriter writer;

    public EventService(EventWriter writer) {
        this.writer = writer;
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
