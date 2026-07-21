package com.compass.app.roadmap.dto;

import java.util.List;

/**
 * Expand more than one module at once (Phase 19), only on the founder's explicit request — the
 * existing single-module "expand on demand" flow stays the default. {@code moduleIds} are run
 * concurrently, capped at a small limit, rather than one after another.
 */
public record ExpandModulesBatchRequest(List<Long> moduleIds) {
}
