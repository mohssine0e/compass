package com.compass.app.roadmap.dto;

import com.compass.app.ai.RoadmapAiService;

import java.util.List;

/**
 * The result of a drafting request. Exactly one shape is populated, keyed by {@code status}:
 * <ul>
 *   <li>{@code needs_clarification} → {@code questions} holds 1–2 questions to answer first;</li>
 *   <li>{@code proposal} → {@code title}, {@code steps}, and {@code skipped} hold a draft the
 *       user edits and owns.</li>
 * </ul>
 * Nothing is persisted here — the user accepts a proposal by creating a roadmap the normal way.
 */
public record GenerateRoadmapResponse(
        String status,
        List<String> questions,
        String title,
        List<ProposedStep> steps,
        List<String> skipped,
        List<String> sources
) {
    /**
     * A proposed step (Phase 7): its text plus its kind (concept|project), relative weight
     * (small|medium|large), the 0-based index of a prerequisite step (or null), and a one-line
     * rationale. The user edits these before accepting.
     */
    public record ProposedStep(String text, String kind, String weight, Integer dependsOn, String rationale) {
        static ProposedStep from(RoadmapAiService.DraftStep s) {
            return new ProposedStep(s.text(), s.kind(), s.weight(), s.dependsOn(), s.rationale());
        }
    }

    public static GenerateRoadmapResponse needsClarification(List<String> questions) {
        return new GenerateRoadmapResponse("needs_clarification", questions, null, null, null, null);
    }

    public static GenerateRoadmapResponse proposal(String title, List<RoadmapAiService.DraftStep> steps,
                                                   List<String> skipped, List<String> sources) {
        return new GenerateRoadmapResponse("proposal", null, title,
                steps.stream().map(ProposedStep::from).toList(), skipped, sources);
    }
}
