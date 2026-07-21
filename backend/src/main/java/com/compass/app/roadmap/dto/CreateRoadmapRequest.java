package com.compass.app.roadmap.dto;

import java.util.List;

/**
 * Create a roadmap. Ways to give its contents:
 * <ul>
 *   <li>{@code steps} — plain text lines, for a hand-written roadmap (Phase 1);</li>
 *   <li>{@code draftSteps} — structured steps accepted from an AI proposal (Phase 7), carrying
 *       kind/weight/prerequisite and any curated resources (Phase 7.5). When present, these are
 *       used and {@code steps} is ignored.</li>
 *   <li>{@code modules} — an accepted top-level module outline (Phase 13): each becomes an empty
 *       child roadmap, expanded into its own steps later via the module-expand flow. Independent
 *       of the other two — a roadmap accepted from an outline sends only this.</li>
 * </ul>
 */
public record CreateRoadmapRequest(
        String title,
        String notes,
        List<String> steps,
        List<DraftStepInput> draftSteps,
        List<ModuleInput> modules
) {
    /**
     * One accepted step. {@code dependsOn} is the 0-based index (within this list) of the
     * prerequisite step, or null — mapped to the real step id once the steps are created.
     * {@code resources} are the curated learning resources to attach.
     */
    public record DraftStepInput(String text, String kind, String weight, Integer dependsOn,
                                 String rationale, List<ResourceInput> resources) {
    }

    /** One curated resource on a step. {@code id} is assigned on save if the client didn't send one. */
    public record ResourceInput(String id, String title, String url, String format,
                                String sourceType, String estimatedTime, String aiGroundingSource) {
    }

    /** One accepted module from an outline (Phase 13): a title and its one-line scope. */
    public record ModuleInput(String title, String scope) {
    }
}
