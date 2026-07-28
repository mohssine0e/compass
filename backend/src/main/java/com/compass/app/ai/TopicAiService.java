package com.compass.app.ai;

import com.compass.app.ai.prompts.TopicPrompts;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Set;

/**
 * The AI layer for Canonical Topic Matching's ambiguous cases (RB-3.4) — only reached when a
 * raw embedding similarity landed in the middle band (neither clearly a match nor clearly not,
 * see {@code TopicMatcherService}). Fast tier: this is a quick judgment call over one candidate
 * topic, not deep reasoning.
 */
@Service
public class TopicAiService {

    private static final Set<String> MATCH_TYPES = Set.of("exact", "subtopic", "prerequisite", "new");

    private final AiJsonGenerator ai;

    public TopicAiService(AiJsonGenerator ai) {
        this.ai = ai;
    }

    /**
     * Judge a new goal against one candidate topic. {@code null} on failure — the caller treats
     * a failed judgment call the same as "new" (no match), never blocking generation on it.
     */
    public MatchJudgment classifyMatch(String goal, String candidateName, List<String> aliases,
                                       List<String> subtopics) {
        JsonNode json = ai.generate(AiTier.FAST, "topic match judgment", TopicPrompts.TOPIC_MATCH_SYSTEM,
                TopicPrompts.topicMatchUser(goal, candidateName, aliases, subtopics));
        if (json == null) {
            return null;
        }
        String matchTypeRaw = AiJsonGenerator.text(json.get("matchType"));
        String matchType = matchTypeRaw != null && MATCH_TYPES.contains(matchTypeRaw) ? matchTypeRaw : "new";
        JsonNode confidenceNode = json.get("confidence");
        double confidence = confidenceNode != null && confidenceNode.isNumber()
                ? Math.max(0.0, Math.min(1.0, confidenceNode.asDouble())) : 0.0;
        String reasoning = AiJsonGenerator.text(json.get("reasoning"));
        return new MatchJudgment(matchType, confidence, reasoning == null ? "" : reasoning.trim());
    }

    /** The result of {@link #classifyMatch} — matchType is exact|subtopic|prerequisite|new. */
    public record MatchJudgment(String matchType, double confidence, String reasoning) {
    }

    private static final Set<String> ADDITION_FIELDS = Set.of("subtopics", "prerequisites", "aliases");

    /**
     * Judge a founder-suggested addition to an existing canonical topic (RB-3.10) — duplicate
     * check, relevance check, and the specific edit to propose. {@code null} on failure.
     */
    public AdditionProposal proposeAddition(String canonicalName, List<String> aliases,
                                            List<String> subtopics, List<String> prerequisites,
                                            String suggestion) {
        JsonNode json = ai.generate(AiTier.FAST, "topic addition", TopicPrompts.TOPIC_ADDITION_SYSTEM,
                TopicPrompts.topicAdditionUser(canonicalName, aliases, subtopics, prerequisites, suggestion));
        if (json == null) {
            return null;
        }
        String fieldRaw = AiJsonGenerator.text(json.get("field"));
        String field = fieldRaw != null && ADDITION_FIELDS.contains(fieldRaw) ? fieldRaw : "subtopics";
        String value = AiJsonGenerator.text(json.get("value"));
        if (value == null || value.isBlank()) {
            return null;
        }
        JsonNode dupNode = json.get("isDuplicate");
        JsonNode relNode = json.get("isRelevant");
        boolean isDuplicate = dupNode != null && dupNode.asBoolean(false);
        boolean isRelevant = relNode == null || relNode.asBoolean(true);
        String reasoning = AiJsonGenerator.text(json.get("reasoning"));
        return new AdditionProposal(field, value.trim(), isDuplicate, isRelevant,
                reasoning == null ? "" : reasoning.trim());
    }

    /** The result of {@link #proposeAddition} — field is subtopics|prerequisites|aliases. */
    public record AdditionProposal(String field, String value, boolean isDuplicate,
                                   boolean isRelevant, String reasoning) {
    }
}
