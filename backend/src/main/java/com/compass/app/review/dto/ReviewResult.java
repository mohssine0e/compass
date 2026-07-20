package com.compass.app.review.dto;

import java.util.List;

/**
 * Cross-thread depth for the review view (Phase 10): recurring {@code threads} across separate
 * things, and a self-talk {@code summary} of where everything stands. {@code enough} is false
 * when there isn't yet enough history for either to be meaningful.
 */
public record ReviewResult(
        List<String> threads,
        String summary,
        boolean enough
) {
}
