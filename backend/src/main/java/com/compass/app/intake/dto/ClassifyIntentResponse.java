package com.compass.app.intake.dto;

import com.compass.app.ai.IntentAiService;

/** The intent classification (RB-5.1) — the founder never sees this directly, just the routing. */
public record ClassifyIntentResponse(String intent, double confidence, String reasoning) {
    public static ClassifyIntentResponse from(IntentAiService.IntentClassification c) {
        return new ClassifyIntentResponse(c.intent().name(), c.confidence(), c.reasoning());
    }
}
