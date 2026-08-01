package com.compass.app.roadmap.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * Insert an accepted new module (Phase 18) — the accept half of "insert a module here".
 * {@code position} is the 0-based index among this roadmap's modules; null appends to the end.
 */
public record InsertModuleRequest(
        // V4-6.1 (2026-07-30 user audit): already required in practice — RoadmapStructureService
        // .insertModule() throws IllegalArgumentException on a blank title.
        @NotBlank(message = "A module needs a title.") String title,
        String scope,
        Integer position
) {
}
