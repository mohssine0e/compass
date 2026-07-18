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
        Map<String, Object> content,
        Instant createdAt,
        Instant updatedAt,
        Instant lastResurfacedAt
) {
    public static EntryResponse from(Entry e) {
        return new EntryResponse(
                e.getId(),
                e.getType(),
                e.getStatus(),
                e.getSignificance(),
                e.getParentId(),
                e.getOrderIndex(),
                e.getContent(),
                e.getCreatedAt(),
                e.getUpdatedAt(),
                e.getLastResurfacedAt()
        );
    }
}
