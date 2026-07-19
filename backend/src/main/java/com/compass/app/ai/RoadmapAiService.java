package com.compass.app.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * The AI layer for drafting and restructuring roadmaps (Phase 4). Kept here in the {@code ai}
 * package with the other Claude/AI calls (CLAUDE.md Section 5) and separate from
 * {@link AiVoiceService}, which only does one-line acknowledgments.
 *
 * <p>Unlike the acknowledgment path, these calls have no plausible plain fallback — you can't
 * draft a roadmap without the model — so every method returns {@code null} when no provider is
 * configured or both fail, and the caller surfaces that to the user as "unavailable, write it
 * yourself" rather than inventing content.
 */
@Service
public class RoadmapAiService {

    private static final Logger log = LoggerFactory.getLogger(RoadmapAiService.class);

    private final AiProperties props;
    private final OpenAiCompatibleChatClient chat;
    private final ObjectMapper mapper;

    public RoadmapAiService(AiProperties props, OpenAiCompatibleChatClient chat, ObjectMapper mapper) {
        this.props = props;
        this.chat = chat;
        this.mapper = mapper;
    }

    /** True when at least one provider could serve a generation request. */
    public boolean isAvailable() {
        return props.getPrimary().isConfigured() || props.getBackup().isConfigured();
    }

    /** 1–2 clarifying questions for a goal, or {@code null} if unavailable / both providers fail. */
    public List<String> clarifyingQuestions(String goal) {
        JsonNode json = generateJson(PromptTemplates.CLARIFY_SYSTEM, PromptTemplates.clarifyUser(goal));
        if (json == null) {
            return null;
        }
        List<String> questions = strings(json.get("questions"));
        return questions.isEmpty() ? null : questions.subList(0, Math.min(2, questions.size()));
    }

    /** A proposed roadmap for a goal + clarifying answers, or {@code null} on failure. */
    public RoadmapDraft proposeRoadmap(String goal, String clarifications) {
        JsonNode json = generateJson(
                PromptTemplates.PROPOSE_SYSTEM, PromptTemplates.proposeUser(goal, clarifications));
        if (json == null) {
            return null;
        }
        String title = text(json.get("title"));
        List<String> steps = strings(json.get("steps"));
        if (steps.isEmpty()) {
            return null;
        }
        return new RoadmapDraft(title, steps);
    }

    /** Smaller sub-steps that replace one stalled step, or {@code null} on failure. */
    public List<String> breakDownStep(String roadmapTitle, String stepText) {
        JsonNode json = generateJson(PromptTemplates.BREAKDOWN_SYSTEM,
                PromptTemplates.breakdownUser(roadmapTitle, stepText));
        if (json == null) {
            return null;
        }
        List<String> steps = strings(json.get("steps"));
        return steps.isEmpty() ? null : steps;
    }

    /**
     * A single prerequisite step to insert before a stalled step, or {@code null} when
     * unavailable, both providers fail, or the model judges nothing is genuinely missing.
     */
    public Prerequisite proposePrerequisite(String roadmapTitle, String stepText, String priorSteps) {
        JsonNode json = generateJson(PromptTemplates.PREREQUISITE_SYSTEM,
                PromptTemplates.prerequisiteUser(roadmapTitle, stepText, priorSteps));
        if (json == null) {
            return null;
        }
        String step = text(json.get("prerequisite"));
        if (step == null || step.isBlank()) {
            return null;
        }
        return new Prerequisite(step, text(json.get("why")));
    }

    /** Try primary then backup with the larger generation budget; parse JSON; null on any failure. */
    private JsonNode generateJson(String system, String user) {
        String raw = complete(props.getPrimary(), system, user);
        if (raw == null) {
            raw = complete(props.getBackup(), system, user);
        }
        return raw == null ? null : parse(raw);
    }

    private String complete(AiProperties.Provider provider, String system, String user) {
        if (!provider.isConfigured()) {
            return null;
        }
        try {
            String out = chat.complete(provider, props.getGenerationTimeoutSeconds(),
                    props.getGenerationMaxTokens(), system, user);
            return out == null || out.isBlank() ? null : out;
        } catch (RuntimeException ex) {
            log.warn("Roadmap AI provider ({}) failed: {}", provider.getModel(), ex.getMessage());
            return null;
        }
    }

    /** Parse the model's reply into JSON, tolerating ```json fences and surrounding prose. */
    private JsonNode parse(String raw) {
        String json = extractJson(raw);
        try {
            return mapper.readTree(json);
        } catch (Exception ex) {
            log.warn("Roadmap AI returned unparseable JSON: {}", ex.getMessage());
            return null;
        }
    }

    /** Pull the first {...} block out of a reply, stripping any code fences around it. */
    private static String extractJson(String raw) {
        String s = raw.trim();
        if (s.startsWith("```")) {
            int firstNewline = s.indexOf('\n');
            if (firstNewline >= 0) {
                s = s.substring(firstNewline + 1);
            }
            int fence = s.lastIndexOf("```");
            if (fence >= 0) {
                s = s.substring(0, fence);
            }
            s = s.trim();
        }
        int open = s.indexOf('{');
        int close = s.lastIndexOf('}');
        return open >= 0 && close > open ? s.substring(open, close + 1) : s;
    }

    private static List<String> strings(JsonNode array) {
        List<String> out = new ArrayList<>();
        if (array != null && array.isArray()) {
            for (JsonNode node : array) {
                String value = text(node);
                if (value != null && !value.isBlank()) {
                    out.add(value.trim());
                }
            }
        }
        return out;
    }

    private static String text(JsonNode node) {
        return node == null || node.isNull() ? null : node.asText(null);
    }

    /** A drafted roadmap the user will edit and own. */
    public record RoadmapDraft(String title, List<String> steps) {
    }

    /** A proposed prerequisite step plus the one-line reason it comes first. */
    public record Prerequisite(String step, String why) {
    }
}
