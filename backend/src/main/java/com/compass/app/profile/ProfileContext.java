package com.compass.app.profile;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Formats a confirmed {@link LearnerProfile} into a compact, plain block for generation prompts
 * (Phase 7). Only what's useful for sizing/skipping a roadmap — skills with confidence, learning
 * traits, and a light touch of resume experience — kept short so it doesn't crowd the prompt.
 */
public final class ProfileContext {

    private ProfileContext() {
    }

    /** A short profile summary for a prompt, or {@code null} if the profile is effectively empty. */
    public static String forPrompt(LearnerProfile profile) {
        if (profile == null) {
            return null;
        }
        StringBuilder sb = new StringBuilder();

        String skills = formatSkills(profile.getSkills());
        if (!skills.isBlank()) {
            sb.append("Skills: ").append(skills).append('\n');
        }

        String traits = formatTraits(profile.getSelfDescription());
        if (!traits.isBlank()) {
            sb.append("Learning traits: ").append(traits).append('\n');
        }

        String experience = formatExperience(profile.getResumeExtracted());
        if (!experience.isBlank()) {
            sb.append("Experience: ").append(experience).append('\n');
        }

        List<String> inferred = profile.getInferredPreferences();
        if (inferred != null && !inferred.isEmpty()) {
            sb.append("Noticed about how they work: ").append(String.join("; ", inferred)).append('\n');
        }

        String learningPrefs = formatLearningPreferences(profile.getLearningPreferences());
        if (!learningPrefs.isBlank()) {
            sb.append("How they like to learn: ").append(learningPrefs).append('\n');
        }

        return sb.length() == 0 ? null : sb.toString().strip();
    }

    /**
     * As {@link #forPrompt}, but for a single module-expansion call (Phase 19): the skills line
     * is pruned to what's actually relevant to {@code moduleTitle}/{@code moduleScope} instead of
     * the founder's whole skill list every time — smaller, cheaper prompts, especially once a
     * profile has accumulated many skills across unrelated domains. Everything else (learning
     * traits, experience, inferred/stated preferences) stays in full: those describe HOW they
     * learn, not WHAT they already know about this specific module, so they're relevant
     * regardless of topic.
     */
    public static String forModulePrompt(LearnerProfile profile, String moduleTitle, String moduleScope) {
        if (profile == null) {
            return null;
        }
        StringBuilder sb = new StringBuilder();

        String skills = formatRelevantSkills(profile.getSkills(), moduleTitle, moduleScope);
        if (!skills.isBlank()) {
            sb.append("Skills: ").append(skills).append('\n');
        }

        String traits = formatTraits(profile.getSelfDescription());
        if (!traits.isBlank()) {
            sb.append("Learning traits: ").append(traits).append('\n');
        }

        String experience = formatExperience(profile.getResumeExtracted());
        if (!experience.isBlank()) {
            sb.append("Experience: ").append(experience).append('\n');
        }

        List<String> inferred = profile.getInferredPreferences();
        if (inferred != null && !inferred.isEmpty()) {
            sb.append("Noticed about how they work: ").append(String.join("; ", inferred)).append('\n');
        }

        String learningPrefs = formatLearningPreferences(profile.getLearningPreferences());
        if (!learningPrefs.isBlank()) {
            sb.append("How they like to learn: ").append(learningPrefs).append('\n');
        }

        return sb.length() == 0 ? null : sb.toString().strip();
    }

    // Below this size, pruning isn't worth the risk of dropping something relevant that just
    // didn't share vocabulary with the module topic — pass the whole (already short) list.
    private static final int SKILL_PRUNE_THRESHOLD = 8;

    private static String formatRelevantSkills(List<Map<String, Object>> skills,
                                                String moduleTitle, String moduleScope) {
        if (skills == null || skills.isEmpty()) {
            return "";
        }
        if (skills.size() <= SKILL_PRUNE_THRESHOLD) {
            return formatSkills(skills);
        }
        java.util.Set<String> topicWords = wordsOf(
                (moduleTitle == null ? "" : moduleTitle) + " " + (moduleScope == null ? "" : moduleScope));
        List<Map<String, Object>> relevant = skills.stream()
                .filter(s -> s.get("name") instanceof String n && overlaps(n, topicWords))
                .toList();
        // Nothing shared vocabulary with the topic — safer to fall back to a capped slice of the
        // full list than to silently show the model nothing about what they already know.
        List<Map<String, Object>> chosen = relevant.isEmpty()
                ? skills.subList(0, Math.min(SKILL_PRUNE_THRESHOLD, skills.size()))
                : relevant;
        return formatSkills(chosen);
    }

    private static java.util.Set<String> wordsOf(String text) {
        java.util.Set<String> words = new java.util.HashSet<>();
        for (String w : text.toLowerCase().split("[^a-z0-9]+")) {
            if (w.length() > 2) {
                words.add(w);
            }
        }
        return words;
    }

    private static boolean overlaps(String skillName, java.util.Set<String> topicWords) {
        for (String w : wordsOf(skillName)) {
            if (topicWords.contains(w)) {
                return true;
            }
        }
        return false;
    }

    /** Plain, comma-joined "key: value" pairs — e.g. "pace: fast, depth: working knowledge". */
    private static String formatLearningPreferences(Map<String, Object> learningPreferences) {
        if (learningPreferences == null || learningPreferences.isEmpty()) {
            return "";
        }
        return learningPreferences.entrySet().stream()
                .filter(e -> e.getValue() instanceof String s && !s.isBlank())
                .map(e -> splitCamel(e.getKey()) + ": " + e.getValue().toString().replace('_', ' '))
                .collect(Collectors.joining(", "));
    }

    private static String splitCamel(String key) {
        return key.replaceAll("([a-z])([A-Z])", "$1 $2").toLowerCase();
    }

    private static String formatSkills(List<Map<String, Object>> skills) {
        if (skills == null) {
            return "";
        }
        return skills.stream()
                .map(s -> {
                    Object name = s.get("name");
                    Object confidence = s.get("confidence");
                    if (!(name instanceof String n) || n.isBlank()) {
                        return null;
                    }
                    return confidence instanceof String c ? n + " (" + c.replace('_', ' ') + ")" : n;
                })
                .filter(s -> s != null)
                .collect(Collectors.joining(", "));
    }

    @SuppressWarnings("unchecked")
    private static String formatTraits(Map<String, Object> selfDescription) {
        if (selfDescription == null) {
            return "";
        }
        Object traits = selfDescription.get("traits");
        if (traits instanceof List<?> list) {
            return list.stream()
                    .filter(t -> t instanceof String s && !s.isBlank())
                    .map(Object::toString)
                    .collect(Collectors.joining(", "));
        }
        return "";
    }

    @SuppressWarnings("unchecked")
    private static String formatExperience(Map<String, Object> resumeExtracted) {
        if (resumeExtracted == null) {
            return "";
        }
        Object experience = resumeExtracted.get("experience");
        if (!(experience instanceof List<?> list)) {
            return "";
        }
        return list.stream()
                .filter(e -> e instanceof Map)
                .map(e -> (Map<String, Object>) e)
                .map(e -> {
                    Object title = e.get("title");
                    Object org = e.get("organization");
                    if (title instanceof String t && org instanceof String o) {
                        return t + " at " + o;
                    }
                    return title instanceof String t ? t : null;
                })
                .filter(s -> s != null)
                .collect(Collectors.joining("; "));
    }
}
