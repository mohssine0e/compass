package com.compass.app.roadmap.dto;

/** Apply an edited module title/scope (Phase 18) — the accept half of "regenerate this module". */
public record UpdateModuleRequest(String title, String scope) {
}
