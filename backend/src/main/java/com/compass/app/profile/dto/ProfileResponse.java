package com.compass.app.profile.dto;

import com.compass.app.profile.LearnerProfile;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Serialized view of the learner profile. {@code confirmed} makes explicit whether the profile
 * has been reviewed/approved at least once — Phase 7 generation only trusts it when true.
 */
public record ProfileResponse(
        List<Map<String, Object>> skills,
        Map<String, Object> resumeExtracted,
        Map<String, Object> selfDescription,
        Map<String, Object> formatPreferences,
        List<String> inferredPreferences,
        boolean confirmed,
        Instant confirmedAt,
        Instant updatedAt
) {
    public static ProfileResponse from(LearnerProfile p) {
        return new ProfileResponse(
                p.getSkills(),
                p.getResumeExtracted(),
                p.getSelfDescription(),
                p.getFormatPreferences(),
                p.getInferredPreferences(),
                p.getConfirmedAt() != null,
                p.getConfirmedAt(),
                p.getUpdatedAt());
    }
}
