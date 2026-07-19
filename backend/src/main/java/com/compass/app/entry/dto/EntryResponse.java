package com.compass.app.entry.dto;

import com.compass.app.entry.Entry;
import com.compass.app.entry.EntryStatus;
import com.compass.app.entry.EntryType;
import com.compass.app.entry.Significance;

import java.time.Instant;
import java.util.Map;

/**
 * Serialized view of an entry. Enums render as their lowercase strings via @JsonValue.
 */
public record EntryResponse(
        Long id,
        EntryType type,
        EntryStatus status,
        Significance significance,
        Long parentId,
        Integer orderIndex,
        Long dependsOn,
        Map<String, Object> content,
        Instant createdAt,
        Instant updatedAt,
        Instant lastResurfacedAt,
        // A short self-talk-voice line for this moment (capture / mark done). Only set on
        // create and mark-done responses; null elsewhere and when no AI provider is set.
        String acknowledgment
) {
    public static EntryResponse from(Entry e) {
        return of(e, null);
    }

    public static EntryResponse of(Entry e, String acknowledgment) {
        return new EntryResponse(
                e.getId(),
                e.getType(),
                e.getStatus(),
                e.getSignificance(),
                e.getParentId(),
                e.getOrderIndex(),
                e.getDependsOn(),
                e.getContent(),
                e.getCreatedAt(),
                e.getUpdatedAt(),
                e.getLastResurfacedAt(),
                acknowledgment
        );
    }
}
