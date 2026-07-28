package com.compass.app.resource;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * A cached "what to focus on" for a resource, keyed by (resourceUrl, topicKey) — the same
 * resource never gets re-processed for the same step topic, even when a different roadmap
 * references it (RES-1). Kept as its own table, not an {@code entries} row: it's a shared,
 * growing reference cache the system builds, not something the founder personally captured
 * (CLAUDE.md Section 4). No TTL — an entry here is reused forever once created.
 */
@Entity
@Table(name = "resource_enrichments")
public class ResourceEnrichment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "resource_url", nullable = false)
    private String resourceUrl;

    @Column(name = "topic_key", nullable = false)
    private String topicKey;

    @Column(nullable = false)
    private String kind;

    @Column(name = "focus_pointer")
    private String focusPointer;

    @Column(name = "segment_start")
    private Integer segmentStart;

    @Column(name = "segment_end")
    private Integer segmentEnd;

    @Column(name = "segment_description")
    private String segmentDescription;

    @Column(nullable = false)
    private String source;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    void onCreate() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }

    public Long getId() {
        return id;
    }

    public String getResourceUrl() {
        return resourceUrl;
    }

    public void setResourceUrl(String resourceUrl) {
        this.resourceUrl = resourceUrl;
    }

    public String getTopicKey() {
        return topicKey;
    }

    public void setTopicKey(String topicKey) {
        this.topicKey = topicKey;
    }

    public String getKind() {
        return kind;
    }

    public void setKind(String kind) {
        this.kind = kind;
    }

    public String getFocusPointer() {
        return focusPointer;
    }

    public void setFocusPointer(String focusPointer) {
        this.focusPointer = focusPointer;
    }

    public Integer getSegmentStart() {
        return segmentStart;
    }

    public void setSegmentStart(Integer segmentStart) {
        this.segmentStart = segmentStart;
    }

    public Integer getSegmentEnd() {
        return segmentEnd;
    }

    public void setSegmentEnd(Integer segmentEnd) {
        this.segmentEnd = segmentEnd;
    }

    public String getSegmentDescription() {
        return segmentDescription;
    }

    public void setSegmentDescription(String segmentDescription) {
        this.segmentDescription = segmentDescription;
    }

    public String getSource() {
        return source;
    }

    public void setSource(String source) {
        this.source = source;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
