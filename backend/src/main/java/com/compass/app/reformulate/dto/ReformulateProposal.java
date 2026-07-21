package com.compass.app.reformulate.dto;

import com.compass.app.roadmap.dto.GenerateRoadmapResponse;

import java.util.List;
import java.util.Map;

/**
 * A proposed reformulation of one step the user found too hard/big/abstract (Phase 8.5) — not
 * applied until approved. Which fields are set depends on {@code kind}:
 * <ul>
 *   <li>{@code break_down} → {@code steps} (smaller sub-steps, same richer shape a module
 *   expansion proposes — kind/weight/rationale/resources, Phase 20 — not just plain text);</li>
 *   <li>{@code add_prerequisite} → {@code prerequisite} + {@code why} (revisit this first);</li>
 *   <li>{@code easier_resources} → {@code resources} (gentler learning resources).</li>
 * </ul>
 * {@code note} is a self-talk line shown when the step has been reformulated repeatedly.
 */
public record ReformulateProposal(
        String kind,
        Long roadmapId,
        Long targetStepId,
        String targetStepText,
        List<GenerateRoadmapResponse.ProposedStep> steps,
        String prerequisite,
        String why,
        List<Map<String, Object>> resources,
        String note
) {
    public static ReformulateProposal breakDown(Long roadmapId, Long stepId, String stepText,
                                                List<GenerateRoadmapResponse.ProposedStep> steps,
                                                String note) {
        return new ReformulateProposal("break_down", roadmapId, stepId, stepText, steps,
                null, null, null, note);
    }

    public static ReformulateProposal prerequisite(Long roadmapId, Long stepId, String stepText,
                                                   String prerequisite, String why, String note) {
        return new ReformulateProposal("add_prerequisite", roadmapId, stepId, stepText, null,
                prerequisite, why, null, note);
    }

    public static ReformulateProposal resources(Long roadmapId, Long stepId, String stepText,
                                                List<Map<String, Object>> resources, String note) {
        return new ReformulateProposal("easier_resources", roadmapId, stepId, stepText, null,
                null, null, resources, note);
    }
}
