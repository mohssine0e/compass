package com.compass.app.roadmap.dto;

import java.util.List;

/** New step order, as the full ordered list of step ids for the roadmap. */
public record ReorderStepsRequest(
        List<Long> stepIds
) {
}
