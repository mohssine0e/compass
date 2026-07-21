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

    /** {avoid:[...]}: resource formats to steer discovery away from (Phase 7.5). */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "format_preferences", columnDefinition = "jsonb")
    private Map<String, Object> formatPreferences;

    /** Behaviour-inferred preferences, confirmed by the founder (Phase 9) — plain strings. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "inferred_preferences", columnDefinition = "jsonb")
    private List<String> inferredPreferences;

    /**
     * Structured how-you-learn options (Phase 15) — selectable, not just prose: {@code pace},
     * {@code theoryVsPractice}, {@code sessionLength}, {@code depth}, {@code exampleVsPrinciple}.
     * Feeds generation (step sizing/resource choice) and guided-session next-action suggestions.
     * See {@link ProfileService#LEARNING_PREFERENCE_OPTIONS} for the allowed value per key.
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "learning_preferences", columnDefinition = "jsonb")
    private Map<String, Object> learningPreferences;

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

    public Map<String, Object> getFormatPreferences() {
        return formatPreferences;
    }

    public void setFormatPreferences(Map<String, Object> formatPreferences) {
        this.formatPreferences = formatPreferences;
    }

    public List<String> getInferredPreferences() {
        return inferredPreferences;
    }

    public void setInferredPreferences(List<String> inferredPreferences) {
        this.inferredPreferences = inferredPreferences;
    }

    public Map<String, Object> getLearningPreferences() {
        return learningPreferences;
    }

    public void setLearningPreferences(Map<String, Object> learningPreferences) {
        this.learningPreferences = learningPreferences;
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
