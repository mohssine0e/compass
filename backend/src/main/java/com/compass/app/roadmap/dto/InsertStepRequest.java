package com.compass.app.roadmap.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * Insert a step into a roadmap. {@code position} is 0-based; omit (or pass past the end)
 * to append.
 */
public record InsertStepRequest(
        // V4-6.1 (2026-07-30 user audit): already required in practice — RoadmapStructureService
        // .insertStep() throws IllegalArgumentException on blank text.
        @NotBlank(message = "A step needs some text.") String text,
        Integer position
) {
}
