package com.compass.app.ai;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Service;

/**
 * The unified intake's first classification (RB-5.1) — what does the founder actually want from
 * one piece of input, across all ten intents. Fast tier: a quick routing decision, not deep
 * reasoning, same as {@link RoadmapAiService#classifyTier}. TASK/MINI/TOPIC/CAREER classification
 * stays a genuinely separate call, reached only for LEARN/PLAN_A_JOURNEY/PREPARE by the existing
 * generation pipeline (RB-2.1) once the founder is routed there — not duplicated here.
 */
@Service
public class IntentAiService {

    private final AiJsonGenerator ai;

    public IntentAiService(AiJsonGenerator ai) {
        this.ai = ai;
    }

    /** {@code null} on failure — the caller falls back to treating input as a plain IDEA capture. */
    public IntentClassification classifyIntent(String input, String profileContext) {
        JsonNode json = ai.generate(AiTier.FAST, "intent classification",
                PromptTemplates.INTENT_CLASSIFY_SYSTEM, PromptTemplates.intentClassifyUser(input, profileContext));
        if (json == null) {
            return null;
        }
        String intentRaw = AiJsonGenerator.text(json.get("intent"));
        Intent intent;
        try {
            intent = intentRaw == null ? null : Intent.valueOf(intentRaw.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException e) {
            intent = null;
        }
        if (intent == null) {
            return null;
        }
        JsonNode confidenceNode = json.get("confidence");
        double confidence = confidenceNode != null && confidenceNode.isNumber()
                ? Math.max(0.0, Math.min(1.0, confidenceNode.asDouble())) : 0.0;
        String reasoning = AiJsonGenerator.text(json.get("reasoning"));
        return new IntentClassification(intent, confidence, reasoning == null ? "" : reasoning.trim());
    }

    public record IntentClassification(Intent intent, double confidence, String reasoning) {
    }
}
