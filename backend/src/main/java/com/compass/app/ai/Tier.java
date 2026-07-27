package com.compass.app.ai;

/**
 * The Roadmap Brain scale classification (RB-1) for a goal, from smallest to largest:
 * {@code TASK} (a single action item, no learning curve), {@code MINI} (a bounded single
 * project with a clear finish line), {@code TOPIC} (open-ended skill/knowledge acquisition,
 * no career implication), {@code CAREER} (an identity/role change, typically months-scale).
 * See {@link PromptTemplates#TIER_CLASSIFY_SYSTEM} for the full definitions and boundary
 * examples used to draw these distinctions.
 *
 * <p>This phase (RB-1) only produces and validates the classification itself — nothing reads
 * this enum to change generation behavior yet.
 */
public enum Tier {
    TASK,
    MINI,
    TOPIC,
    CAREER
}
