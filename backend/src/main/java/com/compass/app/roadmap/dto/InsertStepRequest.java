package com.compass.app.roadmap.dto;

/**
 * Insert a step into a roadmap. {@code position} is 0-based; omit (or pass past the end)
 * to append.
 */
public record InsertStepRequest(
        String text,
        Integer position
) {
}
