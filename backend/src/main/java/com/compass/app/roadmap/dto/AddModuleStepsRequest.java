package com.compass.app.roadmap.dto;

import java.util.List;

/** Accepted steps for one module's expansion (Phase 13) — same shape as a roadmap's draftSteps. */
public record AddModuleStepsRequest(List<CreateRoadmapRequest.DraftStepInput> draftSteps) {
}
