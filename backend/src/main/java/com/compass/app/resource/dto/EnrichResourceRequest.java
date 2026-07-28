package com.compass.app.resource.dto;

/**
 * A deep-view request to enrich one resource (RES-5): {@code resourceUrl} is the resource's
 * real url, {@code stepTopic} is the step's own text — the raw text, not pre-slugified; the
 * backend derives the {@code topic_key} itself so slugification only ever happens in one place.
 * {@code resourceTitle} is the title the frontend already has locally (from the roadmap step's
 * own resource list) — only used by RES-4's video description-fallback path, when there's no
 * transcript to ground a real pointer in and the resource's own title is the only honest thing
 * left to point at.
 */
public record EnrichResourceRequest(String resourceUrl, String stepTopic, String resourceTitle) {
}
