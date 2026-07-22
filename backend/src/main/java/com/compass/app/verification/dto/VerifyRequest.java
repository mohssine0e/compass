package com.compass.app.verification.dto;

/**
 * The user's answer to a step's verification check (Phase 8). {@code answer} is the free-text
 * answer for the free-text formats; {@code selectedIndex} (Phase 26) is the chosen option's
 * 0-based index for a {@code multiple_choice} check instead — at most one is actually read,
 * depending on the pending check's format.
 */
public record VerifyRequest(String answer, Integer selectedIndex) {
}
