package com.compass.app.roadmap.dto;

/**
 * One module's background-drafting status (see {@code ModulePrefetchService}) — {@code status}
 * is {@code PENDING}/{@code DONE}/{@code FAILED}; {@code result} (same shape a direct
 * {@code expandModule} call returns) is set once DONE, {@code error} once FAILED. Poll this
 * instead of blocking on an on-demand expand — a module can already be drafted, ready to review,
 * by the time the founder opens it.
 */
public record ModulePrefetchStatus(Long moduleId, String status, GenerateRoadmapResponse result, String error) {
}
