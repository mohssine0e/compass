package com.compass.app.roadmap;

/**
 * Where a roadmap-generation job (Phase 18) currently is, for the frontend to show as live
 * progress instead of a frozen spinner during a slow AI call. Defined once, server-side, so the
 * frontend only ever displays this value — it never infers meaning from it.
 */
public enum GenerationStage {
    CLARIFYING,
    ASSESSING,
    DRAFTING,
    FINDING_RESOURCES
}
