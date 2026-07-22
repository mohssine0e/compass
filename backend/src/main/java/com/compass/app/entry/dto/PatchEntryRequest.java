package com.compass.app.entry.dto;

import com.compass.app.entry.EntryStatus;
import com.compass.app.entry.Significance;

import java.util.Map;

/**
 * Partial update. Only non-null fields are applied. Phase 1's main use is
 * self-reported completion: {@code {"status": "done"}}.
 *
 * <p>{@code dependsOn} sets a step's prerequisite (Phase 4). Since {@code null} already means
 * "leave unchanged" here, pass {@code 0} (or any non-positive id) to <em>clear</em> the
 * prerequisite instead.
 *
 * <p>{@code projectUrl} (Phase 24) merges into content like {@code notes}/{@code verify} — a
 * blank string clears it. Meant for a {@code kind: "project"} step's public-URL field in the
 * Projects checklist, but not restricted to that; the field is just carried through generically.
 */
public record PatchEntryRequest(
        EntryStatus status,
        Significance significance,
        String text,
        Long dependsOn,
        String notes,
        String verify,
        Map<String, Object> content,
        String projectUrl
) {
}
