package com.compass.app.roadmap.dto;

/**
 * One module's outcome from a batch expansion (Phase 19) — exactly one of {@code result}/
 * {@code error} is set, same "proposal, nothing persisted yet" contract as a single-module
 * expand. The founder reviews and accepts each module independently.
 */
public record ModuleExpansionResult(Long moduleId, GenerateRoadmapResponse result, String error) {
}
