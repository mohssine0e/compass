package com.compass.app.resource.dto;

/**
 * A resource's cached (or freshly computed) enrichment (RESSOURCE_BRAIN_TASKS.md) — a
 * "what to focus on" pointer for a written resource, or a real transcript-backed timestamp
 * range for a video one. {@code kind} is {@code written|video}; the written fields
 * ({@code focusPointer}) and video fields ({@code segmentStart}/{@code segmentEnd}/
 * {@code segmentDescription}) are populated according to which. {@code source} is one of
 * {@code exa_highlight|fetch_fallback|transcript|description_fallback} — the frontend doesn't
 * need to branch on it, it's there for anyone curious where a pointer came from.
 */
public record EnrichmentResponse(String kind, String focusPointer, Integer segmentStart,
                                 Integer segmentEnd, String segmentDescription, String source) {
}
