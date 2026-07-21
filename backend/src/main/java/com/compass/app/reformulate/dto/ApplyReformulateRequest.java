package com.compass.app.reformulate.dto;

import com.compass.app.roadmap.dto.CreateRoadmapRequest;

import java.util.List;
import java.util.Map;

/**
 * The approved (possibly edited) reformulation to apply (Phase 8.5). {@code kind} selects which
 * fields matter: {@code draftSteps} for break_down (same shape module-expansion acceptance uses,
 * Phase 20 — kind/weight/rationale/resources, not just plain text), {@code prerequisite} for
 * add_prerequisite, {@code resources} for easier_resources.
 */
public record ApplyReformulateRequest(
        String kind,
        List<CreateRoadmapRequest.DraftStepInput> draftSteps,
        String prerequisite,
        List<Map<String, Object>> resources
) {
}
