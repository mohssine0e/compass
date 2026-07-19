package com.compass.app.resurfacing.dto;

/**
 * Ask the AI to propose a restructuring of a resurfaced, stalled roadmap's current step
 * (Phase 4). {@code kind} is {@code break_down} (split the step into smaller ones) or
 * {@code add_prerequisite} (propose a missing step to do first). Nothing is changed yet —
 * this only returns a proposal the user edits and approves.
 */
public record RestructureRequest(String kind) {
}
