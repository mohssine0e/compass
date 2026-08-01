package com.compass.app.roadmap.dto;

import jakarta.validation.constraints.NotBlank;

/** Apply an edited module title/scope (Phase 18) — the accept half of "regenerate this module". */
public record UpdateModuleRequest(
        // V4-6.1 (2026-07-30 user audit): already required in practice — RoadmapStructureService
        // .updateModule() throws IllegalArgumentException on a blank title.
        @NotBlank(message = "A module needs a title.") String title,
        String scope
) {
}
