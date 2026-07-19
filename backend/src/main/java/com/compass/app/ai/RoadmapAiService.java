package com.compass.app.ai;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * The AI layer for drafting and restructuring roadmaps (Phase 4). Kept here in the {@code ai}
 * package with the other Claude/AI calls (CLAUDE.md Section 5) and separate from
 * {@link AiVoiceService}, which only does one-line acknowledgments. Shared JSON-generation
 * plumbing lives in {@link AiJsonGenerator}.
 *
 * <p>Unlike the acknowledgment path, these calls have no plausible plain fallback — you can't
 * draft a roadmap without the model — so every method returns {@code null} when no provider is
 * configured or both fail, and the caller surfaces that to the user as "unavailable, write it
 * yourself" rather than inventing content.
 */
@Service
public class RoadmapAiService {

    private final AiJsonGenerator ai;

    public RoadmapAiService(AiJsonGenerator ai) {
        this.ai = ai;
    }

    /** True when at least one provider could serve a generation request. */
    public boolean isAvailable() {
        return ai.isAvailable();
    }

    /** 1–2 clarifying questions for a goal, or {@code null} if unavailable / both providers fail. */
    public List<String> clarifyingQuestions(String goal) {
        JsonNode json = ai.generate("roadmap clarifying questions",
                PromptTemplates.CLARIFY_SYSTEM, PromptTemplates.clarifyUser(goal));
        if (json == null) {
            return null;
        }
        List<String> questions = AiJsonGenerator.strings(json.get("questions"));
        return questions.isEmpty() ? null : questions.subList(0, Math.min(2, questions.size()));
    }

    /** A proposed roadmap for a goal + clarifying answers, or {@code null} on failure. */
    public RoadmapDraft proposeRoadmap(String goal, String clarifications) {
        JsonNode json = ai.generate("roadmap proposal",
                PromptTemplates.PROPOSE_SYSTEM, PromptTemplates.proposeUser(goal, clarifications));
        if (json == null) {
            return null;
        }
        String title = AiJsonGenerator.text(json.get("title"));
        List<String> steps = AiJsonGenerator.strings(json.get("steps"));
        if (steps.isEmpty()) {
            return null;
        }
        return new RoadmapDraft(title, steps);
    }

    /** Smaller sub-steps that replace one stalled step, or {@code null} on failure. */
    public List<String> breakDownStep(String roadmapTitle, String stepText) {
        JsonNode json = ai.generate("step breakdown", PromptTemplates.BREAKDOWN_SYSTEM,
                PromptTemplates.breakdownUser(roadmapTitle, stepText));
        if (json == null) {
            return null;
        }
        List<String> steps = AiJsonGenerator.strings(json.get("steps"));
        return steps.isEmpty() ? null : steps;
    }

    /**
     * A single prerequisite step to insert before a stalled step, or {@code null} when
     * unavailable, both providers fail, or the model judges nothing is genuinely missing.
     */
    public Prerequisite proposePrerequisite(String roadmapTitle, String stepText, String priorSteps) {
        JsonNode json = ai.generate("prerequisite proposal", PromptTemplates.PREREQUISITE_SYSTEM,
                PromptTemplates.prerequisiteUser(roadmapTitle, stepText, priorSteps));
        if (json == null) {
            return null;
        }
        String step = AiJsonGenerator.text(json.get("prerequisite"));
        if (step == null || step.isBlank()) {
            return null;
        }
        return new Prerequisite(step, AiJsonGenerator.text(json.get("why")));
    }

    /** A drafted roadmap the user will edit and own. */
    public record RoadmapDraft(String title, List<String> steps) {
    }

    /** A proposed prerequisite step plus the one-line reason it comes first. */
    public record Prerequisite(String step, String why) {
    }
}
