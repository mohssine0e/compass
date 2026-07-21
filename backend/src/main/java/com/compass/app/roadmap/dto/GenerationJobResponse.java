package com.compass.app.roadmap.dto;

/**
 * Polled progress of a roadmap-generation job (Phase 18). {@code stage} is only meaningful while
 * {@code status} is {@code "PENDING"} — a fixed, server-defined value the frontend only displays,
 * never infers meaning from. {@code result} is null until {@code status} is {@code "DONE"};
 * {@code error} is null unless {@code status} is {@code "FAILED"}.
 */
public record GenerationJobResponse(
        String status,
        String stage,
        GenerateRoadmapResponse result,
        String error
) {
}
