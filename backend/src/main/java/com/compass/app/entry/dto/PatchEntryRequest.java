package com.compass.app.entry.dto;

import com.compass.app.entry.EntryStatus;
import com.compass.app.entry.Significance;

import java.util.Map;

/**
 * Partial update. Only non-null fields are applied. Phase 1's main use is
 * self-reported completion: {@code {"status": "done"}}.
 */
public record PatchEntryRequest(
        EntryStatus status,
        Significance significance,
        String text,
        Map<String, Object> content
) {
}
