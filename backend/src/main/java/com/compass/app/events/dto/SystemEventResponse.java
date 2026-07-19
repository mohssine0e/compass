package com.compass.app.events.dto;

import com.compass.app.events.EventSeverity;
import com.compass.app.events.EventSource;
import com.compass.app.events.SystemEvent;

import java.time.Instant;
import java.util.Map;

/** Serialized view of a system event. Enums render as their lowercase strings via @JsonValue. */
public record SystemEventResponse(
        Long id,
        Instant occurredAt,
        EventSource source,
        String category,
        String message,
        Map<String, Object> context,
        EventSeverity severity
) {
    public static SystemEventResponse from(SystemEvent e) {
        return new SystemEventResponse(
                e.getId(),
                e.getOccurredAt(),
                e.getSource(),
                e.getCategory(),
                e.getMessage(),
                e.getContext(),
                e.getSeverity());
    }
}
