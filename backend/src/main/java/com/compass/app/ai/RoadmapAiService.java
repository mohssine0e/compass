package com.compass.app.ai;

import com.compass.app.ai.SearchGroundingService.Result;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashSet;
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
     * 0–4 clarifying questions for a goal (Phase 17), adaptively sized and sharpened by the
     * profile context if given — deliberately no fixed count or default pair of dimensions; a
     * narrow goal with a rich profile can come back empty, a broad one can come back with several.
     * Returns an empty list (not {@code null}) when the model genuinely has nothing to ask;
     * {@code null} only when unavailable / both providers fail.
     */
    public List<String> clarifyingQuestions(String goal, String profileContext) {
        JsonNode json = ai.generate("roadmap clarifying questions",
                PromptTemplates.CLARIFY_SYSTEM, PromptTemplates.clarifyUser(goal, profileContext));
        if (json == null) {
            return null;
        }
        List<String> questions = AiJsonGenerator.strings(json.get("questions"));
        return questions.size() <= 4 ? questions : questions.subList(0, 4);
    }

    /**
     * An optional single follow-up round (Phase 17), conditioned on the first round's actual
     * answers. Returns an empty list (the expected common case) when nothing genuinely follows
     * up; {@code null} only when unavailable / both providers fail.
     */
    public List<String> followUpQuestions(String goal, String firstRoundQa, String profileContext) {
        JsonNode json = ai.generate("roadmap follow-up questions", PromptTemplates.FOLLOWUP_CLARIFY_SYSTEM,
                PromptTemplates.followUpClarifyUser(goal, firstRoundQa, profileContext));
        if (json == null) {
            return null;
        }
        List<String> questions = AiJsonGenerator.strings(json.get("questions"));
        return questions.size() <= 2 ? questions : questions.subList(0, 2);
    }

    /**
     * A top-level module outline for a big goal (Phase 13) — the few major areas, each a title
     * plus a one-line scope, drafted before any individual steps. {@code null} on failure.
     */
    public RoadmapOutline moduleOutline(String goal, String clarifications, String profileContext,
                                        String groundingContext) {
        JsonNode json = ai.generate("roadmap outline", PromptTemplates.OUTLINE_SYSTEM,
                PromptTemplates.outlineUser(goal, clarifications, profileContext, groundingContext));
        if (json == null) {
            return null;
        }
        String title = AiJsonGenerator.text(json.get("title"));
        String interpretation = AiJsonGenerator.text(json.get("interpretation"));
        List<OutlineModule> modules = parseModules(json.get("modules"));
        if (modules.isEmpty()) {
            return null;
        }
        List<String> skipped = AiJsonGenerator.strings(json.get("skipped"));
        return new RoadmapOutline(title, interpretation, modules, skipped);
    }

    /**
     * Expand ONE module of a roadmap into its ordered steps (Phase 13), scoped to that module.
     * {@code null} on failure.
     */
    public List<DraftStep> expandModule(String roadmapTitle, String moduleTitle, String moduleScope,
                                        String profileContext, String groundingContext) {
        JsonNode json = ai.generate("module expansion", PromptTemplates.EXPAND_MODULE_SYSTEM,
                PromptTemplates.expandModuleUser(roadmapTitle, moduleTitle, moduleScope,
                        profileContext, groundingContext));
        if (json == null) {
            return null;
        }
        List<DraftStep> steps = parseSteps(json.get("steps"));
        return steps.isEmpty() ? null : steps;
    }

    private static List<OutlineModule> parseModules(JsonNode array) {
        List<OutlineModule> modules = new ArrayList<>();
        if (array == null || !array.isArray()) {
            return modules;
        }
        for (JsonNode node : array) {
            String title = AiJsonGenerator.text(node.get("title"));
            if (title == null || title.isBlank()) {
                continue;
            }
            String scope = AiJsonGenerator.text(node.get("scope"));
            modules.add(new OutlineModule(title.trim(), scope == null ? null : scope.trim()));
        }
        return modules;
    }

    private static final Set<String> KINDS = Set.of("concept", "project");
    private static final Set<String> WEIGHTS = Set.of("small", "medium", "large");
    private static final Set<String> FORMATS =
            Set.of("written", "video", "interactive", "repo", "book_chapter");
    private static final Set<String> SOURCE_TYPES = Set.of("official_docs", "community", "tutorial");

    /**
     * Up to 3 real learning resources per step, drawn only from the given search results (real
     * URLs, never invented), never in an avoided format, and never a url already in
     * {@code excludeUrls} or reused across two steps in this same call — the fix for resources
     * duplicating across a roadmap (Phase 13). Returns a list aligned to {@code stepTexts}
     * (empty list for a step with no fitting resources); an all-empty result when unavailable or
     * nothing fits. Used for Phase 7.5 resource discovery.
     */
    public List<List<Resource>> suggestResources(String goal, List<String> stepTexts,
                                                 List<Result> groundingResults, List<String> avoidFormats,
                                                 Set<String> excludeUrls) {
        List<List<Resource>> perStep = new ArrayList<>();
        for (int i = 0; i < stepTexts.size(); i++) {
            perStep.add(new ArrayList<>());
        }
        if (groundingResults == null || groundingResults.isEmpty()) {
            return perStep; // resources are a grounded feature; without search there are none
        }

        Set<String> realUrls = new HashSet<>();
        StringBuilder results = new StringBuilder();
        for (Result r : groundingResults) {
            if (r.url() != null && !r.url().isBlank()) {
                realUrls.add(r.url());
                results.append("- ").append(r.title()).append(" [").append(r.url()).append("]");
                if (r.content() != null && !r.content().isBlank()) {
                    results.append(": ").append(r.content());
                }
                results.append('\n');
            }
        }
        if (realUrls.isEmpty()) {
            return perStep;
        }

        Set<String> avoid = avoidFormats == null ? Set.of() : new HashSet<>(avoidFormats);
        Set<String> used = excludeUrls == null ? new HashSet<>() : new HashSet<>(excludeUrls);
        JsonNode json = ai.generate("resource suggestions", PromptTemplates.RESOURCE_SUGGEST_SYSTEM,
                PromptTemplates.resourceSuggestUser(goal, stepTexts, results.toString(), avoidFormats,
                        List.copyOf(used)));
        if (json == null || json.get("steps") == null || !json.get("steps").isArray()) {
            return perStep;
        }

        for (JsonNode stepNode : json.get("steps")) {
            JsonNode indexNode = stepNode.get("index");
            if (indexNode == null || !indexNode.isInt()) {
                continue;
            }
            int index = indexNode.asInt();
            if (index < 0 || index >= perStep.size()) {
                continue;
            }
            List<Resource> resources = perStep.get(index);
            for (JsonNode resNode : arrayOrEmpty(stepNode.get("resources"))) {
                Resource resource = toResource(resNode, realUrls, avoid);
                // Belt-and-suspenders: even if the model repeats a url despite the prompt rule,
                // never let a duplicate through code-side.
                if (resource != null && !used.contains(resource.url()) && resources.size() < 3) {
                    resources.add(resource);
                    used.add(resource.url());
                }
            }
        }
        return perStep;
    }

    private static Resource toResource(JsonNode node, Set<String> realUrls, Set<String> avoid) {
        String url = AiJsonGenerator.text(node.get("url"));
        String title = AiJsonGenerator.text(node.get("title"));
        // Only real URLs from the search results — drop anything invented.
        if (url == null || !realUrls.contains(url) || title == null || title.isBlank()) {
            return null;
        }
        String format = valueIn(AiJsonGenerator.text(node.get("format")), FORMATS, "written");
        if (avoid.contains(format)) {
            return null; // never suggest an avoided format
        }
        String sourceType = valueIn(AiJsonGenerator.text(node.get("source_type")), SOURCE_TYPES, "community");
        String estimatedTime = AiJsonGenerator.text(node.get("estimated_time"));
        String groundingSource = AiJsonGenerator.text(node.get("ai_grounding_source"));
        return new Resource(title.trim(), url, format, sourceType,
                estimatedTime == null ? null : estimatedTime.trim(),
                groundingSource == null ? null : groundingSource.trim());
    }

    private static Iterable<JsonNode> arrayOrEmpty(JsonNode node) {
        return node != null && node.isArray() ? node : List.of();
    }

    /** 2–4 short bullets of what a roadmap step covers, or {@code null} on failure (Phase 7.5). */
    public List<String> stepCovers(String roadmapTitle, String stepText) {
        JsonNode json = ai.generate("step covers",
                PromptTemplates.COVERS_SYSTEM, PromptTemplates.coversUser(roadmapTitle, stepText));
        if (json == null) {
            return null;
        }
        List<String> covers = AiJsonGenerator.strings(json.get("covers"));
        return covers.isEmpty() ? null : covers;
    }

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
     * One proposed step. {@code kind} is concept|project, {@code weight} is small|medium|large,
     * {@code dependsOn} is the 0-based index of an earlier prerequisite step (or null), and
     * {@code rationale} says why it's here / why the prerequisite comes first.
     */
    public record DraftStep(String text, String kind, String weight, Integer dependsOn, String rationale) {
    }

    /** A proposed prerequisite step plus the one-line reason it comes first. */
    public record Prerequisite(String step, String why) {
    }

    /**
     * A drafted top-level outline (Phase 13): the roadmap title, its modules, and any whole
     * modules skipped because the profile shows they're already known. {@code interpretation}
     * (Phase 17) is non-null only when the goal was ambiguous enough to need a stated reading, or
     * when little/no clarification was given and the model is stating its assumptions instead.
     */
    public record RoadmapOutline(String title, String interpretation, List<OutlineModule> modules,
                                 List<String> skipped) {
    }

    /** One proposed module: a short title and a one-line scope. Its steps come later, on expand. */
    public record OutlineModule(String title, String scope) {
    }

    /**
     * A suggested learning resource for a step (Phase 7.5). {@code url} is always a real link
     * from the search grounding; {@code aiGroundingSource} names which result it came from.
     */
    public record Resource(String title, String url, String format, String sourceType,
                           String estimatedTime, String aiGroundingSource) {
    }
}
