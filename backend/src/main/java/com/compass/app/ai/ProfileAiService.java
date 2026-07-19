package com.compass.app.ai;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The AI layer for the learner profile (Phase 6): pulling structured data out of resume text,
 * and interpreting a free-text self-description into a few traits. Both are <em>guesses</em> —
 * the caller shows them back for confirmation and never trusts them silently (CLAUDE.md).
 * Returns {@code null} when the AI can't help, so the caller can say so rather than invent.
 */
@Service
public class ProfileAiService {

    private final AiJsonGenerator ai;

    public ProfileAiService(AiJsonGenerator ai) {
        this.ai = ai;
    }

    public boolean isAvailable() {
        return ai.isAvailable();
    }

    /**
     * Structured {skills, experience, education} pulled from resume text, or {@code null} when
     * the AI is unavailable / the reply is unusable. Skills are plain names (the founder sets
     * confidence themselves); experience/education are small {field: value} maps.
     */
    public Map<String, Object> extractResume(String resumeText) {
        JsonNode json = ai.generate("resume extraction",
                PromptTemplates.RESUME_EXTRACT_SYSTEM, PromptTemplates.resumeExtractUser(resumeText));
        if (json == null) {
            return null;
        }
        Map<String, Object> extracted = new LinkedHashMap<>();
        extracted.put("skills", AiJsonGenerator.strings(json.get("skills")));
        extracted.put("experience", objects(json.get("experience"), "title", "organization", "summary"));
        extracted.put("education", objects(json.get("education"), "credential", "institution"));
        boolean empty = ((List<?>) extracted.get("skills")).isEmpty()
                && ((List<?>) extracted.get("experience")).isEmpty()
                && ((List<?>) extracted.get("education")).isEmpty();
        return empty ? null : extracted;
    }

    /**
     * A few short learning-style traits interpreted from a free-text self-description, or
     * {@code null} when unavailable / unusable. These are shown back for confirmation.
     */
    public List<String> interpretSelfDescription(String text) {
        JsonNode json = ai.generate("self-description interpretation",
                PromptTemplates.SELF_DESCRIPTION_SYSTEM, PromptTemplates.selfDescriptionUser(text));
        if (json == null) {
            return null;
        }
        List<String> traits = AiJsonGenerator.strings(json.get("traits"));
        return traits.isEmpty() ? null : traits;
    }

    /** The elements of a JSON array as small maps of the given string fields (blanks dropped). */
    private static List<Map<String, Object>> objects(JsonNode array, String... fields) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (array != null && array.isArray()) {
            for (JsonNode node : array) {
                Map<String, Object> map = new LinkedHashMap<>();
                for (String field : fields) {
                    String value = AiJsonGenerator.text(node.get(field));
                    if (value != null && !value.isBlank()) {
                        map.put(field, value.trim());
                    }
                }
                if (!map.isEmpty()) {
                    out.add(map);
                }
            }
        }
        return out;
    }
}
