package com.compass.app.profile;

import com.compass.app.profile.dto.SaveProfileRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The learner profile is a singleton: this service always reads or writes the one row,
 * creating it on first touch. AI-derived parts are set through the propose→approve→apply flow
 * (the confirmation screen), never silently trusted (CLAUDE.md).
 */
@Service
public class ProfileService {

    /** The confidence levels a skill may carry; anything else is dropped to null. */
    static final Set<String> CONFIDENCE_LEVELS = Set.of("just_started", "comfortable", "solid");

    private final LearnerProfileRepository repository;

    public ProfileService(LearnerProfileRepository repository) {
        this.repository = repository;
    }

    /** The current profile, creating an empty one if none exists yet. */
    @Transactional
    public LearnerProfile getOrCreate() {
        return repository.findFirstByOrderByIdAsc()
                .orElseGet(() -> repository.save(new LearnerProfile()));
    }

    /**
     * Save the reviewed profile and mark it confirmed — saving from the review screen is the
     * act of approving it (CLAUDE.md: nothing here is trusted by generation until confirmed).
     * Skills are sanitized to {name, confidence?} with a valid confidence or none.
     */
    @Transactional
    public LearnerProfile save(SaveProfileRequest req) {
        LearnerProfile profile = getOrCreate();
        profile.setSkills(sanitizeSkills(req.skills()));
        profile.setResumeExtracted(req.resumeExtracted());
        profile.setSelfDescription(req.selfDescription());
        profile.setConfirmedAt(Instant.now());
        return repository.save(profile);
    }

    /** Keep only skills with a real name; normalize confidence to a known level or null. */
    static List<Map<String, Object>> sanitizeSkills(List<Map<String, Object>> raw) {
        List<Map<String, Object>> clean = new ArrayList<>();
        if (raw == null) {
            return clean;
        }
        for (Map<String, Object> skill : raw) {
            if (skill == null) {
                continue;
            }
            Object nameValue = skill.get("name");
            if (!(nameValue instanceof String name) || name.isBlank()) {
                continue;
            }
            Map<String, Object> normalized = new LinkedHashMap<>();
            normalized.put("name", name.trim());
            Object confidence = skill.get("confidence");
            if (confidence instanceof String c && CONFIDENCE_LEVELS.contains(c)) {
                normalized.put("confidence", c);
            }
            clean.add(normalized);
        }
        return clean;
    }
}
