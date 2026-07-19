package com.compass.app.roadmap.dto;

import java.util.List;

/**
 * A roadmap-drafting request (Phase 4). Sent twice in one drafting flow:
 * <ol>
 *   <li>with just a {@code goal} and no {@code clarifications} → the AI returns 1–2 clarifying
 *       questions;</li>
 *   <li>with the same goal plus the answered {@code clarifications} → the AI returns a proposed,
 *       editable step breakdown.</li>
 * </ol>
 * A {@code null} clarifications list means "ask me the questions"; a present list (even empty)
 * means "propose the steps now".
 */
public record GenerateRoadmapRequest(
        String goal,
        List<Clarification> clarifications
) {
    /** One clarifying question and the user's answer to it. */
    public record Clarification(String question, String answer) {
    }
}
