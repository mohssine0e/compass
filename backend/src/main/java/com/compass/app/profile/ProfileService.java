package com.compass.app.profile;

import com.compass.app.ai.ProfileAiService;
import com.compass.app.profile.dto.SaveProfileRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The learner profile is a singleton: this service always reads or writes the one row,
 * creating it on first touch. AI-derived parts are set through the propose→approve→apply flow
 * (the confirmation screen), never silently trusted (CLAUDE.md).
 */
@Service
public class ProfileService {

    /** The confidence levels a skill may carry; anything else is dropped to null. */
    static final Set<String> CONFIDENCE_LEVELS = Set.of("just_started", "comfortable", "solid");

    /** The resource formats a founder can avoid — matches the resource format enum. */
    static final Set<String> FORMATS = Set.of("written", "video", "interactive", "repo", "book_chapter");

    /**
     * Structured learning-preference keys and their allowed values (Phase 15) — selectable, not
     * free text, so generation can act on them deliberately. Any other key or value is dropped.
     */
    static final Map<String, Set<String>> LEARNING_PREFERENCE_OPTIONS = Map.of(
            "pace", Set.of("slow", "steady", "fast"),
            "theoryVsPractice", Set.of("theory_first", "balanced", "practice_first"),
            "sessionLength", Set.of("short", "medium", "long"),
            "depth", Set.of("overview", "working_knowledge", "deep_mastery"),
            "exampleVsPrinciple", Set.of("example_first", "principle_first")
    );

    private final LearnerProfileRepository repository;
    private final ResumeTextExtractor resumeText;
    private final ProfileAiService profileAi;

    public ProfileService(LearnerProfileRepository repository, ResumeTextExtractor resumeText,
                          ProfileAiService profileAi) {
        this.repository = repository;
        this.resumeText = resumeText;
        this.profileAi = profileAi;
    }

    /** The current profile, creating an empty one if none exists yet. */
    @Transactional
    public LearnerProfile getOrCreate() {
        return repository.findFirstByOrderByIdAsc()
                .orElseGet(() -> repository.save(new LearnerProfile()));
    }

    /**
     * The profile only if it's been confirmed at least once — generation must not read an
     * unconfirmed profile (CLAUDE.md: AI reads of the person are guesses until approved).
     */
    @Transactional(readOnly = true)
    public java.util.Optional<LearnerProfile> confirmedProfile() {
        return repository.findFirstByOrderByIdAsc().filter(p -> p.getConfirmedAt() != null);
    }

    /**
     * Save the reviewed profile and mark it confirmed — saving from the review screen is the
     * act of approving it (CLAUDE.md: nothing here is trusted by generation until confirmed).
     * Skills are sanitized to {name, confidence?} with a valid confidence or none.
     */
    @Transactional
    public LearnerProfile save(SaveProfileRequest req) {
        LearnerProfile profile = getOrCreate();
        profile.setSkills(sanitizeSkills(req.skills()));
        profile.setResumeExtracted(req.resumeExtracted());
        profile.setSelfDescription(req.selfDescription());
        profile.setFormatPreferences(sanitizeFormatPreferences(req.formatPreferences()));
        profile.setInferredPreferences(sanitizeStrings(req.inferredPreferences()));
        profile.setLearningPreferences(sanitizeLearningPreferences(req.learningPreferences()));
        profile.setConfirmedAt(Instant.now());
        return repository.save(profile);
    }

    /** Keep only known preference keys with a value from that key's allowed option set. */
    static Map<String, Object> sanitizeLearningPreferences(Map<String, Object> raw) {
        if (raw == null) {
            return null;
        }
        Map<String, Object> clean = new LinkedHashMap<>();
        for (Map.Entry<String, Set<String>> option : LEARNING_PREFERENCE_OPTIONS.entrySet()) {
            Object value = raw.get(option.getKey());
            if (value instanceof String s && option.getValue().contains(s)) {
                clean.put(option.getKey(), s);
            }
        }
        return clean.isEmpty() ? null : clean;
    }

    /** Keep only non-blank strings, trimmed; null when empty. */
    static List<String> sanitizeStrings(List<String> raw) {
        if (raw == null) {
            return null;
        }
        List<String> clean = raw.stream()
                .filter(s -> s != null && !s.isBlank())
                .map(String::trim)
                .toList();
        return clean.isEmpty() ? null : clean;
    }

    /**
     * Keep only known format names under {@code avoid}/{@code prefer}; drop anything else.
     * {@code prefer} was briefly removed earlier in Phase 20 as dead scaffolding (sanitized but
     * never set by any UI or read by generation) — re-added here now that it has a real source
     * and a real reader: the resource-usage feedback loop's confirmed behavioral bias (a founder
     * confirms "you lean toward written over video" the same way they confirm an avoid
     * preference), fed into {@code resourceSuggestUser} as a soft bias, never a hard requirement.
     */
    static Map<String, Object> sanitizeFormatPreferences(Map<String, Object> raw) {
        if (raw == null) {
            return null;
        }
        Map<String, Object> clean = new LinkedHashMap<>();
        for (String key : List.of("avoid", "prefer")) {
            Object value = raw.get(key);
            if (value instanceof List<?> list) {
                List<String> formats = list.stream()
                        .filter(f -> f instanceof String s && FORMATS.contains(s))
                        .map(Object::toString)
                        .distinct()
                        .toList();
                if (!formats.isEmpty()) {
                    clean.put(key, formats);
                }
            }
        }
        return clean.isEmpty() ? null : clean;
    }

    /**
     * Extract structured facts from an uploaded resume — a <em>proposal</em> the founder edits
     * and confirms before it's saved, never trusted silently (CLAUDE.md). The raw file and its
     * text are processed in memory and discarded here; only the structure returned (and only if
     * the founder later confirms) is ever persisted. Throws {@link IllegalStateException} when
     * the AI can't help, so the caller can fall back to manual entry.
     */
    public Map<String, Object> extractResume(MultipartFile file) {
        String text = resumeText.extract(file); // throws IllegalArgumentException on a bad file
        if (!profileAi.isAvailable()) {
            throw new IllegalStateException("Resume reading is unavailable right now — add skills by hand.");
        }
        Map<String, Object> extracted = profileAi.extractResume(text);
        if (extracted == null) {
            throw new IllegalStateException(
                    "Couldn't pull structured info from that resume — add skills by hand.");
        }
        return extracted;
    }

    /**
     * Interpret a free-text self-description into a few learning-style traits — a proposal the
     * founder reviews before it's saved (CLAUDE.md: AI reads of the person are guesses). Throws
     * {@link IllegalStateException} when the AI can't help; the founder can still save their own
     * words without traits.
     */
    public List<String> interpretSelfDescription(String text) {
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("Write a sentence or two first.");
        }
        if (!profileAi.isAvailable()) {
            throw new IllegalStateException(
                    "Interpreting is unavailable right now — you can still save your own words.");
        }
        List<String> traits = profileAi.interpretSelfDescription(text);
        if (traits == null) {
            throw new IllegalStateException(
                    "Couldn't pull traits from that — you can still save your own words.");
        }
        return traits;
    }

    /** Keep only skills with a real name; normalize confidence to a known level or null. */
    static List<Map<String, Object>> sanitizeSkills(List<Map<String, Object>> raw) {
        List<Map<String, Object>> clean = new ArrayList<>();
        if (raw == null) {
            return clean;
        }
        for (Map<String, Object> skill : raw) {
            if (skill == null) {
                continue;
            }
            Object nameValue = skill.get("name");
            if (!(nameValue instanceof String name) || name.isBlank()) {
                continue;
            }
            Map<String, Object> normalized = new LinkedHashMap<>();
            normalized.put("name", name.trim());
            Object confidence = skill.get("confidence");
            if (confidence instanceof String c && CONFIDENCE_LEVELS.contains(c)) {
                normalized.put("confidence", c);
            }
            // "resume" marks a skill pulled from an uploaded resume, kept only so the profile
            // screen can group resume-derived skills separately from hand-added ones (Phase 23) —
            // any other value is dropped rather than trusted as free-form data.
            if ("resume".equals(skill.get("source"))) {
                normalized.put("source", "resume");
            }
            clean.add(normalized);
        }
        return clean;
    }
}
