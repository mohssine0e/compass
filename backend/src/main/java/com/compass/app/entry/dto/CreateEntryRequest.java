package com.compass.app.entry.dto;

import com.compass.app.entry.EntryType;
import com.compass.app.entry.Significance;

import java.util.Map;

/**
 * Capture payload. The low-friction path sends just {@code text} (type defaults to idea).
 * Richer callers may pass a full {@code content} map, an explicit {@code type}, or parent/
 * order for roadmap steps.
 */
public record CreateEntryRequest(
        EntryType type,
        String text,
        Significance significance,
        Map<String, Object> content,
        Long parentId,
        Integer orderIndex
) {
}
