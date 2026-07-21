package com.compass.app.resurfacing.dto;

import com.compass.app.roadmap.dto.GenerateRoadmapResponse;

import java.util.List;

/**
 * A proposed restructuring of a stalled step — not yet applied. The user edits and approves
 * it before anything changes (CLAUDE.md Phase 4: never auto-edit silently). Which fields are
 * populated depends on {@code kind}:
 * <ul>
 *   <li>{@code break_down} → {@code steps} holds the smaller sub-steps that would replace it,
 *       same richer shape a module expansion proposes (kind/weight/rationale/resources, Phase
 *       20 — not just plain text);</li>
 *   <li>{@code add_prerequisite} → {@code prerequisite} holds the step to do first, {@code why}
 *       a one-line reason in the self-talk voice.</li>
 * </ul>
 */
public record RestructureProposal(
        String kind,
        Long roadmapId,
        Long targetStepId,
        String targetStepText,
        List<GenerateRoadmapResponse.ProposedStep> steps,
        String prerequisite,
        String why
) {
    public static RestructureProposal breakDown(Long roadmapId, Long targetStepId,
                                                String targetStepText,
                                                List<GenerateRoadmapResponse.ProposedStep> steps) {
        return new RestructureProposal("break_down", roadmapId, targetStepId, targetStepText,
                steps, null, null);
    }

    public static RestructureProposal prerequisite(Long roadmapId, Long targetStepId,
                                                   String targetStepText, String prerequisite,
                                                   String why) {
        return new RestructureProposal("add_prerequisite", roadmapId, targetStepId, targetStepText,
                null, prerequisite, why);
    }
}
