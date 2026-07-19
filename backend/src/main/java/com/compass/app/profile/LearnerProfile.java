package com.compass.app.profile;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The founder's learner profile — a singleton (one current profile edited in place, not a
 * collection). Its AI-derived parts ({@link #resumeExtracted}, {@link #selfDescription}) are
 * guesses shown back for confirmation; {@link #confirmedAt} stays null until approved at least
 * once, and Phase 7 generation must not read the profile before then (CLAUDE.md Section 4).
 */
@Entity
@Table(name = "learner_profile")
public class LearnerProfile {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** List of {name, confidence} maps; confidence optional (just_started/comfortable/solid). */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    private List<Map<String, Object>> skills = new ArrayList<>();

    /** Structured resume data ({skills, experience, education}); the raw file is never stored. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "resume_extracted", columnDefinition = "jsonb")
    private Map<String, Object> resumeExtracted;

    /** {raw, traits}: the founder's free text plus AI-interpreted learning traits. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "self_description", columnDefinition = "jsonb")
    private Map<String, Object> selfDescription;

    @Column(name = "confirmed_at")
    private Instant confirmedAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    @PreUpdate
    void touch() {
        updatedAt = Instant.now();
        if (skills == null) {
            skills = new ArrayList<>();
        }
    }

    public Long getId() {
        return id;
    }

    public List<Map<String, Object>> getSkills() {
        return skills;
    }

    public void setSkills(List<Map<String, Object>> skills) {
        this.skills = skills;
    }

    public Map<String, Object> getResumeExtracted() {
        return resumeExtracted;
    }

    public void setResumeExtracted(Map<String, Object> resumeExtracted) {
        this.resumeExtracted = resumeExtracted;
    }

    public Map<String, Object> getSelfDescription() {
        return selfDescription;
    }

    public void setSelfDescription(Map<String, Object> selfDescription) {
        this.selfDescription = selfDescription;
    }

    public Instant getConfirmedAt() {
        return confirmedAt;
    }

    public void setConfirmedAt(Instant confirmedAt) {
        this.confirmedAt = confirmedAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
