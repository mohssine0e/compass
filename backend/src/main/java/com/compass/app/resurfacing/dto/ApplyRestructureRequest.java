package com.compass.app.resurfacing.dto;

import com.compass.app.roadmap.dto.CreateRoadmapRequest;

import java.util.List;

/**
 * The user's approved restructuring, sent back (possibly edited) to be applied (Phase 4).
 * For {@code break_down}, {@code draftSteps} are the sub-steps that replace {@code targetStepId}
 * (same shape module-expansion acceptance uses, Phase 20); for {@code add_prerequisite},
 * {@code prerequisite} is the step to insert before it.
 */
public record ApplyRestructureRequest(
        String kind,
        Long targetStepId,
        List<CreateRoadmapRequest.DraftStepInput> draftSteps,
        String prerequisite
) {
}
