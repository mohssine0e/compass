package com.compass.app.roadmap.dto;

import java.util.List;

/** Accept an edited "replan remaining modules" round (Phase 18). */
public record ReplanModulesRequest(List<ReplanModuleItem> modules) {
}
