package com.compass.app.resource.dto;

/**
 * A deep-view request to enrich one resource (RES-5): {@code resourceUrl} is the resource's
 * real url, {@code stepTopic} is the step's own text — the raw text, not pre-slugified; the
 * backend derives the {@code topic_key} itself so slugification only ever happens in one place.
 */
public record EnrichResourceRequest(String resourceUrl, String stepTopic) {
}
