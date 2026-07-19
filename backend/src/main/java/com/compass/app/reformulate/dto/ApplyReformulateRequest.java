package com.compass.app.reformulate.dto;

import java.util.List;
import java.util.Map;

/**
 * The approved (possibly edited) reformulation to apply (Phase 8.5). {@code kind} selects which
 * fields matter: {@code steps} for break_down, {@code prerequisite} for add_prerequisite,
 * {@code resources} for easier_resources.
 */
public record ApplyReformulateRequest(
        String kind,
        List<String> steps,
        String prerequisite,
        List<Map<String, Object>> resources
) {
}
