package com.compass.app.roadmap.dto;

import java.util.List;

/**
 * Create a roadmap. Two ways to give the steps:
 * <ul>
 *   <li>{@code steps} — plain text lines, for a hand-written roadmap (Phase 1);</li>
 *   <li>{@code draftSteps} — structured steps accepted from an AI proposal (Phase 7), carrying
 *       kind/weight/prerequisite. When present, these are used and {@code steps} is ignored.</li>
 * </ul>
 */
public record CreateRoadmapRequest(
        String title,
        String notes,
        List<String> steps,
        List<DraftStepInput> draftSteps
) {
    /**
     * One accepted step. {@code dependsOn} is the 0-based index (within this list) of the
     * prerequisite step, or null — mapped to the real step id once the steps are created.
     */
    public record DraftStepInput(String text, String kind, String weight, Integer dependsOn) {
    }
}
