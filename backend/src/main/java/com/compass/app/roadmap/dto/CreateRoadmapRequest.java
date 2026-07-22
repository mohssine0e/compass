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
 * {@code assessment} (Phase 18, optional) carries the shared goal-scope read computed when this
 * roadmap was drafted, stored on the roadmap so a later module-expand call reads the same numbers
 * instead of re-guessing scope from scratch.
 */
public record CreateRoadmapRequest(
        String title,
        String notes,
        List<String> steps,
        List<DraftStepInput> draftSteps,
        List<ModuleInput> modules,
        AssessmentInput assessment
) {
    /**
     * One accepted step. {@code dependsOn} is the 0-based index (within this list) of the
     * prerequisite step, or null — mapped to the real step id once the steps are created.
     * {@code dependsOnEntryId} (Phase 18) is a real, already-existing step id instead — used when
     * a module's step depends on something from an earlier module, resolved directly with no
     * index translation needed. At most one of the two is ever set. {@code resources} are the
     * curated learning resources to attach. {@code skeletonOnly} (Phase 19) marks a step accepted
     * from the emergency titles-only fallback, so it can be flagged in the UI and found later by
     * the background retry that fills in full detail.
     */
    public record DraftStepInput(String text, String kind, String weight, Integer dependsOn,
                                 Long dependsOnEntryId, String rationale,
                                 List<ResourceInput> resources, boolean skeletonOnly) {
    }

    /** One curated resource on a step. {@code id} is assigned on save if the client didn't send one. */
    public record ResourceInput(String id, String title, String url, String format,
                                String sourceType, String estimatedTime, String aiGroundingSource) {
    }

    /** One accepted module from an outline (Phase 13): a title and its one-line scope. */
    public record ModuleInput(String title, String scope) {
    }

    /** The goal-scope read (Phase 18, {@code archetype} added Phase 24) computed when this
     * roadmap was drafted. */
    public record AssessmentInput(int complexity, Integer estimatedTotalHours, String domain,
                                  String priorLevel, String shape, String archetype) {
    }
}
