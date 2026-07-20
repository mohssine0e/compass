package com.compass.app.roadmap.dto;

/** Body for archiving/unarchiving a roadmap (Phase 12): {@code {"archived": true|false}}. */
public record ArchiveRoadmapRequest(boolean archived) {
}
