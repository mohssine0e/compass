package com.compass.app.topic;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * A shared, growing reference catalog of topics the founder has generated roadmaps for before
 * (RB-3) — kept as its own table, not an {@code entries} row, since it's a reference catalog
 * the system builds up, not something the founder personally captured (CLAUDE.md Section 4).
 * {@code embedding} is computed once at creation ({@link #createdFrom}) or enhancement
 * (RB-3.10) and never recomputed per match attempt — the incoming goal's own embedding is
 * fresh every time instead (see {@code EmbeddingService}).
 */
@Entity
@Table(name = "canonical_topics")
public class CanonicalTopic {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "topic_id", nullable = false, unique = true)
    private String topicId;

    @Column(name = "canonical_name", nullable = false)
    private String canonicalName;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    private List<String> aliases = new ArrayList<>();

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    private List<String> subtopics = new ArrayList<>();

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    private List<String> prerequisites = new ArrayList<>();

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "related_topics", nullable = false, columnDefinition = "jsonb")
    private List<String> relatedTopics = new ArrayList<>();

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    private List<Double> embedding = new ArrayList<>();

    @Column(name = "created_from")
    private String createdFrom;

    // The real roadmap (entries.id) this canonical topic is "the" roadmap for — needed to open
    // it directly on an exact match (RB-3.7). Nullable: a topic can outlive the roadmap it was
    // created from.
    @Column(name = "roadmap_entry_id")
    private Long roadmapEntryId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "usage_count", nullable = false)
    private int usageCount = 1;

    @Column(name = "last_used_at", nullable = false)
    private Instant lastUsedAt;

    @PrePersist
    void onCreate() {
        Instant now = Instant.now();
        if (createdAt == null) {
            createdAt = now;
        }
        if (lastUsedAt == null) {
            lastUsedAt = now;
        }
    }

    // --- getters / setters ---

    public Long getId() {
        return id;
    }

    public String getTopicId() {
        return topicId;
    }

    public void setTopicId(String topicId) {
        this.topicId = topicId;
    }

    public String getCanonicalName() {
        return canonicalName;
    }

    public void setCanonicalName(String canonicalName) {
        this.canonicalName = canonicalName;
    }

    public List<String> getAliases() {
        return aliases;
    }

    public void setAliases(List<String> aliases) {
        this.aliases = aliases;
    }

    public List<String> getSubtopics() {
        return subtopics;
    }

    public void setSubtopics(List<String> subtopics) {
        this.subtopics = subtopics;
    }

    public List<String> getPrerequisites() {
        return prerequisites;
    }

    public void setPrerequisites(List<String> prerequisites) {
        this.prerequisites = prerequisites;
    }

    public List<String> getRelatedTopics() {
        return relatedTopics;
    }

    public void setRelatedTopics(List<String> relatedTopics) {
        this.relatedTopics = relatedTopics;
    }

    public List<Double> getEmbedding() {
        return embedding;
    }

    public void setEmbedding(List<Double> embedding) {
        this.embedding = embedding;
    }

    public String getCreatedFrom() {
        return createdFrom;
    }

    public void setCreatedFrom(String createdFrom) {
        this.createdFrom = createdFrom;
    }

    public Long getRoadmapEntryId() {
        return roadmapEntryId;
    }

    public void setRoadmapEntryId(Long roadmapEntryId) {
        this.roadmapEntryId = roadmapEntryId;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public int getUsageCount() {
        return usageCount;
    }

    public void setUsageCount(int usageCount) {
        this.usageCount = usageCount;
    }

    public Instant getLastUsedAt() {
        return lastUsedAt;
    }

    public void setLastUsedAt(Instant lastUsedAt) {
        this.lastUsedAt = lastUsedAt;
    }
}
