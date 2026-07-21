package com.compass.app.roadmap.dto;

import java.util.List;

/**
 * A roadmap-drafting request (Phase 4, reshaped by Phase 17). Sent in up to three turns:
 * <ol>
 *   <li>with just a {@code goal} and no {@code clarifications} → the AI returns 0–4 clarifying
 *       questions (or, if it has nothing to ask, drafts straight away);</li>
 *   <li>with the goal plus the first round's answered {@code clarifications} and
 *       {@code skipFollowUp: false} → the AI checks for one genuine follow-up round; if it has a
 *       real follow-up, the response is another {@code needs_clarification} with those questions
 *       (usually empty, in which case it drafts straight away instead);</li>
 *   <li>if a follow-up round came back, resubmit with all rounds' answers merged into
 *       {@code clarifications} and {@code skipFollowUp: true} → drafts, no further rounds.</li>
 * </ol>
 * A {@code null} clarifications list means "ask me the questions"; a present list (even empty)
 * means "draft now" — {@code skipFollowUp} only matters on that second/third case, to cap the
 * conversation at one optional follow-up round and let the founder's "just draft it" skip
 * affordance bypass the follow-up check entirely.
 */
public record GenerateRoadmapRequest(
        String goal,
        List<Clarification> clarifications,
        boolean skipFollowUp
) {
    /** One clarifying question and the user's answer to it. */
    public record Clarification(String question, String answer) {
    }
}
