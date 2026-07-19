package com.compass.app.profile.dto;

import java.util.List;
import java.util.Map;

/**
 * Save the reviewed profile (Phase 6). Sent from the confirmation/overview screen with the
 * full current state — saving is the act of confirming, so it marks the profile approved.
 * {@code resumeExtracted} / {@code selfDescription} may be null when the founder hasn't used
 * those; {@code skills} is the manually-curated list of {name, confidence} entries.
 */
public record SaveProfileRequest(
        List<Map<String, Object>> skills,
        Map<String, Object> resumeExtracted,
        Map<String, Object> selfDescription
) {
}
