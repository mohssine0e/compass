package com.compass.app.resource.dto;

import java.util.List;

/**
 * Ask for learning resources for an already-drafted, not-yet-persisted batch of steps —
 * {@code roadmapId} is null for a brand-new flat-goal proposal (no roadmap exists yet to dedupe
 * resource urls against) and the real id for a reformulate/resurfacing break-down on an existing
 * roadmap.
 */
public record SuggestResourcesRequest(String scope, List<String> stepTexts, Long roadmapId) {
}
