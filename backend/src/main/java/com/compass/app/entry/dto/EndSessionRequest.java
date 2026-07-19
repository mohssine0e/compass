package com.compass.app.entry.dto;

/**
 * Ends a work session on a step (Phase 7.5). All fields optional: {@code resourceUsed} is a
 * resource id the session was spent on, {@code userFeedback} a short note, {@code completed}
 * whether the step now feels done. The duration is computed server-side from the start time.
 */
public record EndSessionRequest(
        String resourceUsed,
        String userFeedback,
        Boolean completed
) {
}
