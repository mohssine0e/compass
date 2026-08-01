package com.compass.app.roadmap.dto;

import jakarta.validation.constraints.NotEmpty;

import java.util.List;

/** New step order, as the full ordered list of step ids for the roadmap. */
public record ReorderStepsRequest(
        // V4-6.1 (2026-07-30 user audit): already required in practice — RoadmapStructureService
        // .reorderSteps() throws IllegalArgumentException on a null/mismatched list.
        @NotEmpty(message = "Reorder must include exactly this roadmap's current steps.") List<Long> stepIds
) {
}
