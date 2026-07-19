package com.compass.app.profile;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The learner profile is a singleton: this service always reads or writes the one row,
 * creating it on first touch. AI-derived parts are set through the propose→approve→apply flow
 * (the confirmation screen), never silently trusted (CLAUDE.md).
 */
@Service
public class ProfileService {

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
}
