package com.compass.app.profile;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface LearnerProfileRepository extends JpaRepository<LearnerProfile, Long> {

    /** The single profile row, if it exists — the table is a singleton (CLAUDE.md Section 4). */
    Optional<LearnerProfile> findFirstByOrderByIdAsc();
}
