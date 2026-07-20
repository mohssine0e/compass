package com.compass.app.roadmap.dto;

import com.compass.app.entry.Entry;
import com.compass.app.entry.EntryStatus;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/**
 * A roadmap plus its tree of nodes and a "where am I" summary (Phase 13). A simple roadmap is a
 * tree one level deep (its steps are the children) and reads as a plain linear list; a big one
 * nests modules and substeps. Progress and "current step" are computed over leaf steps, wherever
 * they sit in the tree, so a parent's numbers always reflect real units of work.
 */
public record RoadmapResponse(
        Long id,
        String title,
        String notes,
        EntryStatus status,
        String verify,
        Instant createdAt,
        Instant updatedAt,
        List<RoadmapNodeResponse> children,
        Progress progress
) {
    /**
     * @param total          number of leaf steps
     * @param done           leaf steps marked done
     * @param currentStepId  id of the first not-done/dropped leaf in tree order — where you
     *                       actually are; null when nothing is left
     * @param currentStepText that leaf's text, for the list view; null when complete
     */
    public record Progress(int total, int done, Long currentStepId, String currentStepText) {
    }

    public static RoadmapResponse of(Entry roadmap, Function<Long, List<Entry>> childrenOf) {
        String title = asString(roadmap, "title");
        String notes = asString(roadmap, "notes");
        String verify = asString(roadmap, "verify"); // roadmap-wide default: null/light/full

        List<RoadmapNodeResponse> children = childrenOf.apply(roadmap.getId()).stream()
                .map(child -> RoadmapNodeResponse.of(child, childrenOf))
                .toList();

        List<RoadmapNodeResponse> leaves = new ArrayList<>();
        RoadmapNodeResponse.collectLeaves(children, leaves);

        int total = leaves.size();
        int done = (int) leaves.stream().filter(l -> l.status() == EntryStatus.DONE).count();
        RoadmapNodeResponse current = leaves.stream()
                .filter(l -> l.status() != EntryStatus.DONE && l.status() != EntryStatus.DROPPED)
                .findFirst()
                .orElse(null);
        Long currentStepId = current == null ? null : current.id();
        String currentStepText = current == null ? null
                : (current.content() != null && current.content().get("text") instanceof String s ? s : null);

        return new RoadmapResponse(
                roadmap.getId(),
                title,
                notes,
                roadmap.getStatus(),
                verify,
                roadmap.getCreatedAt(),
                roadmap.getUpdatedAt(),
                children,
                new Progress(total, done, currentStepId, currentStepText)
        );
    }

    private static String asString(Entry entry, String key) {
        Object value = entry.getContent() != null ? entry.getContent().get(key) : null;
        return value instanceof String s ? s : null;
    }
}
