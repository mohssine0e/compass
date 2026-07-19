package com.compass.app.roadmap.dto;

import com.compass.app.entry.Entry;
import com.compass.app.entry.EntryStatus;
import com.compass.app.entry.dto.EntryResponse;

import java.time.Instant;
import java.util.List;

/**
 * A roadmap plus its ordered steps and a small "where am I" progress summary.
 */
public record RoadmapResponse(
        Long id,
        String title,
        String notes,
        EntryStatus status,
        String verify,
        Instant createdAt,
        Instant updatedAt,
        List<EntryResponse> steps,
        Progress progress
) {
    /**
     * @param total             number of steps
     * @param done              steps marked done
     * @param currentOrderIndex order_index of the first step not yet done/dropped —
     *                          i.e. where you actually are; null when nothing is left
     */
    public record Progress(int total, int done, Integer currentOrderIndex) {
    }

    public static RoadmapResponse of(Entry roadmap, List<Entry> steps) {
        String title = asString(roadmap, "title");
        String notes = asString(roadmap, "notes");
        String verify = asString(roadmap, "verify"); // roadmap-wide default: null/light/full

        List<EntryResponse> stepDtos = steps.stream().map(EntryResponse::from).toList();

        int total = steps.size();
        int done = (int) steps.stream()
                .filter(s -> s.getStatus() == EntryStatus.DONE)
                .count();
        Integer currentOrderIndex = steps.stream()
                .filter(s -> s.getStatus() != EntryStatus.DONE
                        && s.getStatus() != EntryStatus.DROPPED)
                .map(Entry::getOrderIndex)
                .findFirst()
                .orElse(null);

        return new RoadmapResponse(
                roadmap.getId(),
                title,
                notes,
                roadmap.getStatus(),
                verify,
                roadmap.getCreatedAt(),
                roadmap.getUpdatedAt(),
                stepDtos,
                new Progress(total, done, currentOrderIndex)
        );
    }

    private static String asString(Entry entry, String key) {
        Object value = entry.getContent() != null ? entry.getContent().get(key) : null;
        return value instanceof String s ? s : null;
    }
}
