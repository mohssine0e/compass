package com.compass.app.roadmap.dto;

import java.util.List;

/**
 * The result of a drafting request. Exactly one shape is populated, keyed by {@code status}:
 * <ul>
 *   <li>{@code needs_clarification} → {@code questions} holds 1–2 questions to answer first;</li>
 *   <li>{@code proposal} → {@code title} and {@code steps} hold a draft the user edits and owns.</li>
 * </ul>
 * Nothing is persisted here — the user accepts a proposal by creating a roadmap the normal way.
 */
public record GenerateRoadmapResponse(
        String status,
        List<String> questions,
        String title,
        List<String> steps,
        List<String> skipped
) {
    public static GenerateRoadmapResponse needsClarification(List<String> questions) {
        return new GenerateRoadmapResponse("needs_clarification", questions, null, null, null);
    }

    public static GenerateRoadmapResponse proposal(String title, List<String> steps, List<String> skipped) {
        return new GenerateRoadmapResponse("proposal", null, title, steps, skipped);
    }
}
