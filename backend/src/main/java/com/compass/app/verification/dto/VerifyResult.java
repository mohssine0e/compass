package com.compass.app.verification.dto;

/**
 * The outcome of answering a verification check (Phase 8). On {@code passed}, the step has been
 * marked done. Otherwise {@code gap} is the specific thing that was missed, in the self-talk
 * voice, and the step stays open.
 *
 * <p>{@code suggestedPrerequisite}/{@code suggestedPrerequisiteWhy} (Phase 20, both nullable) are
 * set only when the named gap plausibly maps to a genuinely missing prerequisite, not just a
 * shaky answer — the founder can accept it (reusing the existing add_prerequisite
 * propose→approve→apply flow) or dismiss it; nothing is ever inserted silently.
 */
public record VerifyResult(boolean passed, String gap,
                           String suggestedPrerequisite, String suggestedPrerequisiteWhy) {
}
