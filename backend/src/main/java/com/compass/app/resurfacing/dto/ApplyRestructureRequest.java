package com.compass.app.resurfacing.dto;

import java.util.List;

/**
 * The user's approved restructuring, sent back (possibly edited) to be applied (Phase 4).
 * For {@code break_down}, {@code steps} are the sub-steps that replace {@code targetStepId};
 * for {@code add_prerequisite}, {@code prerequisite} is the step to insert before it.
 */
public record ApplyRestructureRequest(
        String kind,
        Long targetStepId,
        List<String> steps,
        String prerequisite
) {
}
