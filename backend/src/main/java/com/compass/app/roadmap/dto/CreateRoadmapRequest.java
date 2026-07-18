package com.compass.app.roadmap.dto;

import java.util.List;

/**
 * Create a roadmap with an ordered list of steps. For v1 the founder writes the steps
 * manually (AI-generated roadmaps are a later nice-to-have, per TASKS.md Phase 1).
 */
public record CreateRoadmapRequest(
        String title,
        String notes,
        List<String> steps
) {
}
