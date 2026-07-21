package com.compass.app.roadmap.dto;

/**
 * One module in a "replan remaining modules" round (Phase 18) — proposed with its real
 * {@code moduleId} on the way out, accepted (possibly edited) with the same id on the way back
 * in, so {@link com.compass.app.roadmap.RoadmapService#applyReplan} knows exactly which module
 * each redraft belongs to.
 */
public record ReplanModuleItem(Long moduleId, String title, String scope) {
}
