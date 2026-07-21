package com.compass.app.profile.dto;

import java.util.List;

/**
 * Behaviour-inferred preferences to propose to the founder (Phase 9). Each is a plain,
 * grounded observation; {@code avoidFormat}/{@code preferFormat} (Phase 20) are set when the
 * observation implies avoiding or leaning toward a resource format (so accepting it can also
 * update format preferences). {@code basis} says what the inference was drawn from, so a thin
 * inference reads honestly.
 */
public record InferenceResult(
        List<InferredPreference> preferences,
        String basis
) {
    public record InferredPreference(String text, String avoidFormat, String preferFormat) {
    }
}
