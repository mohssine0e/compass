package com.compass.app.ai.dto;

import com.compass.app.ai.RoadmapAiService.TierClassification;

/** A single tier-classification result for the RB-1 debug screen's "Classify" button. */
public record ClassificationResult(String goal, String tier, double confidence, String reasoning) {

    public static ClassificationResult of(String goal, TierClassification c) {
        return new ClassificationResult(goal, c.tier().name(), c.confidence(), c.reasoning());
    }
}
