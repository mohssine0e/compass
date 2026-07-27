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
 *
 * <p>{@code tier} (RB-2, nullable) is the TASK/MINI/TOPIC/CAREER classification from
 * {@code classifyTier} — computed once, on the very first turn (when {@code clarifications} is
 * still {@code null}), and echoed back by the frontend on every later turn of the same goal so
 * it survives across the stateless clarify/follow-up round trip without a second classification
 * call. Always {@code null} on that first turn; the server fills it in and hands it back on the
 * response for the frontend to carry forward.
 *
 * <p>{@code skipTopicMatch} (RB-3.5, first-turn only like {@code tier}) — set when the founder
 * already saw a Canonical Topic Match proposal for this exact goal and chose "start a separate
 * one" / confirmed "new" — resubmitting without it would just show the same match prompt again.
 */
public record GenerateRoadmapRequest(
        String goal,
        List<Clarification> clarifications,
        boolean skipFollowUp,
        String tier,
        boolean skipTopicMatch
) {
    /** One clarifying question and the user's answer to it. */
    public record Clarification(String question, String answer) {
    }
}
