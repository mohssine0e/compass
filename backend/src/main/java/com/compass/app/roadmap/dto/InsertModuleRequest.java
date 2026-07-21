package com.compass.app.roadmap.dto;

/**
 * Insert an accepted new module (Phase 18) — the accept half of "insert a module here".
 * {@code position} is the 0-based index among this roadmap's modules; null appends to the end.
 */
public record InsertModuleRequest(String title, String scope, Integer position) {
}
