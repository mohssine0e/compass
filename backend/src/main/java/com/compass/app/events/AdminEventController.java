package com.compass.app.events;

import com.compass.app.events.dto.SystemEventResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Read-only admin window into recent {@link SystemEvent}s (Phase 5). No authentication by
 * design — Compass is single-user right now (CLAUDE.md "No auth yet"). Most recent first,
 * filterable by {@code source} and {@code severity}; invalid filter values yield a 400 via
 * the enum parsing.
 */
@RestController
@RequestMapping("/admin/events")
public class AdminEventController {

    private final EventService events;

    public AdminEventController(EventService events) {
        this.events = events;
    }

    @GetMapping
    public List<SystemEventResponse> list(
            @RequestParam(required = false) String source,
            @RequestParam(required = false) String severity,
            @RequestParam(required = false) Integer limit) {
        EventSource sourceFilter = source == null || source.isBlank() ? null : EventSource.fromValue(source);
        EventSeverity severityFilter =
                severity == null || severity.isBlank() ? null : EventSeverity.fromValue(severity);
        return events.recent(sourceFilter, severityFilter, limit).stream()
                .map(SystemEventResponse::from)
                .toList();
    }
}
