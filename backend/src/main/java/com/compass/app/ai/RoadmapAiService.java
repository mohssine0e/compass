package com.compass.app.ai;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

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

    /**
     * 1–2 clarifying questions for a goal, sharpened by the profile context if given, or
     * {@code null} if unavailable / both providers fail.
     */
    public List<String> clarifyingQuestions(String goal, String profileContext) {
        JsonNode json = ai.generate("roadmap clarifying questions",
                PromptTemplates.CLARIFY_SYSTEM, PromptTemplates.clarifyUser(goal, profileContext));
        if (json == null) {
            return null;
        }
        List<String> questions = AiJsonGenerator.strings(json.get("questions"));
        return questions.isEmpty() ? null : questions.subList(0, Math.min(2, questions.size()));
    }

    /**
     * A proposed roadmap for a goal + clarifying answers, using the profile context to skip what
     * the user already knows (each skip stated plainly). {@code null} on failure.
     */
    public RoadmapDraft proposeRoadmap(String goal, String clarifications, String profileContext) {
        JsonNode json = ai.generate("roadmap proposal", PromptTemplates.PROPOSE_SYSTEM,
                PromptTemplates.proposeUser(goal, clarifications, profileContext));
        if (json == null) {
            return null;
        }
        String title = AiJsonGenerator.text(json.get("title"));
        List<DraftStep> steps = parseSteps(json.get("steps"));
        if (steps.isEmpty()) {
            return null;
        }
        List<String> skipped = AiJsonGenerator.strings(json.get("skipped"));
        return new RoadmapDraft(title, steps, skipped);
    }

    private static final Set<String> KINDS = Set.of("concept", "project");
    private static final Set<String> WEIGHTS = Set.of("small", "medium", "large");

    /** Parse and sanitize the structured step array; skips entries without real text. */
    private static List<DraftStep> parseSteps(JsonNode array) {
        List<DraftStep> steps = new ArrayList<>();
        if (array == null || !array.isArray()) {
            return steps;
        }
        for (JsonNode node : array) {
            String text = AiJsonGenerator.text(node.get("text"));
            if (text == null || text.isBlank()) {
                continue;
            }
            String kind = valueIn(AiJsonGenerator.text(node.get("kind")), KINDS, "concept");
            String weight = valueIn(AiJsonGenerator.text(node.get("weight")), WEIGHTS, "medium");
            Integer dependsOn = node.get("dependsOn") != null && node.get("dependsOn").isInt()
                    ? node.get("dependsOn").asInt() : null;
            String rationale = AiJsonGenerator.text(node.get("rationale"));
            steps.add(new DraftStep(text.trim(), kind, weight, dependsOn,
                    rationale == null ? null : rationale.trim()));
        }
        // A dependsOn index is only valid if it points at an earlier step; drop anything else.
        List<DraftStep> validated = new ArrayList<>();
        for (int i = 0; i < steps.size(); i++) {
            DraftStep s = steps.get(i);
            Integer dep = s.dependsOn() != null && s.dependsOn() >= 0 && s.dependsOn() < i
                    ? s.dependsOn() : null;
            validated.add(new DraftStep(s.text(), s.kind(), s.weight(), dep, s.rationale()));
        }
        return validated;
    }

    private static String valueIn(String value, Set<String> allowed, String fallback) {
        return value != null && allowed.contains(value) ? value : fallback;
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

    /**
     * A drafted roadmap the user will edit and own. {@code skipped} lists topics left out
     * because the profile shows they're already known, each with its plainly-stated reason.
     */
    public record RoadmapDraft(String title, List<DraftStep> steps, List<String> skipped) {
    }

    /**
     * One proposed step. {@code kind} is concept|project, {@code weight} is small|medium|large,
     * {@code dependsOn} is the 0-based index of an earlier prerequisite step (or null), and
     * {@code rationale} says why it's here / why the prerequisite comes first.
     */
    public record DraftStep(String text, String kind, String weight, Integer dependsOn, String rationale) {
    }

    /** A proposed prerequisite step plus the one-line reason it comes first. */
    public record Prerequisite(String step, String why) {
    }
}
