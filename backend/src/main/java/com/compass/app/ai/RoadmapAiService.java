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
    private final AiGenerationCache cache;

    public RoadmapAiService(AiJsonGenerator ai, AiGenerationCache cache) {
        this.ai = ai;
        this.cache = cache;
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
        JsonNode json = ai.generate(AiTier.FAST, "roadmap clarifying questions",
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
        JsonNode json = ai.generate(AiTier.FAST, "roadmap follow-up questions", PromptTemplates.FOLLOWUP_CLARIFY_SYSTEM,
                PromptTemplates.followUpClarifyUser(goal, firstRoundQa, profileContext));
        if (json == null) {
            return null;
        }
        List<String> questions = AiJsonGenerator.strings(json.get("questions"));
        return questions.size() <= 2 ? questions : questions.subList(0, 2);
    }

    /**
     * A shared, structured read of how big/complex a goal is (Phase 18) — computed once so the
     * flat/nested gate, module count, and step count all read the same numbers instead of each
     * prompt independently re-guessing scope from raw text. {@code null} on failure; the caller
     * falls back to today's un-assessed behavior (nested, mid-range) rather than blocking
     * generation on it.
     */
    public GoalAssessment assessGoal(String goal, String clarifications, String profileContext,
                                     String groundingContext) {
        JsonNode json = ai.generate(AiTier.FAST, "goal assessment", PromptTemplates.ASSESS_SYSTEM,
                PromptTemplates.assessUser(goal, clarifications, profileContext, groundingContext));
        if (json == null) {
            return null;
        }
        JsonNode complexityNode = json.get("complexity");
        int complexity = complexityNode != null && complexityNode.isInt()
                ? Math.max(1, Math.min(5, complexityNode.asInt())) : 3;
        JsonNode hoursNode = json.get("estimatedTotalHours");
        Integer hours = hoursNode != null && hoursNode.isIntegralNumber() ? hoursNode.asInt() : null;
        String domain = AiJsonGenerator.text(json.get("domain"));
        String priorLevel = AiJsonGenerator.text(json.get("priorLevel"));
        String shape = "flat".equals(AiJsonGenerator.text(json.get("shape"))) ? "flat" : "nested";
        String archetypeRaw = AiJsonGenerator.text(json.get("archetype"));
        String archetype = ARCHETYPES.contains(archetypeRaw) ? archetypeRaw : "topic_deep_dive";
        return new GoalAssessment(complexity, hours, domain, priorLevel, shape, archetype);
    }

    private static final Set<String> ARCHETYPES = Set.of("quick_task", "topic_deep_dive", "career_path");

    /** A plain-text summary of an assessment for other prompts to read; {@code null} if none. */
    public static String assessmentContext(GoalAssessment a) {
        if (a == null) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        sb.append("shape ").append(a.shape()).append(", complexity ").append(a.complexity()).append("/5");
        if (a.archetype() != null) {
            sb.append(", archetype: ").append(a.archetype());
        }
        if (a.estimatedTotalHours() != null) {
            sb.append(", ~").append(a.estimatedTotalHours()).append(" hours total");
        }
        if (a.domain() != null && !a.domain().isBlank()) {
            sb.append(", domain: ").append(a.domain());
        }
        if (a.priorLevel() != null && !a.priorLevel().isBlank()) {
            sb.append(", starting point: ").append(a.priorLevel());
        }
        return sb.toString();
    }

    /**
     * A top-level module outline for a big goal (Phase 13) — the few major areas, each a title
     * plus a one-line scope, drafted before any individual steps. {@code null} on failure.
     */
    public RoadmapOutline moduleOutline(String goal, String clarifications, String profileContext,
                                        String groundingContext, String assessmentContext,
                                        String domain) {
        String cacheKey = AiGenerationCache.key("outline", goal, clarifications, profileContext,
                groundingContext, assessmentContext, domain);
        RoadmapOutline cached = cache.get(cacheKey);
        if (cached != null) {
            return cached;
        }
        JsonNode json = ai.generate(AiTier.HEAVY, "roadmap outline", PromptTemplates.OUTLINE_SYSTEM,
                PromptTemplates.outlineUser(goal, clarifications, profileContext, groundingContext,
                        assessmentContext, domain));
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
        RoadmapOutline outline = new RoadmapOutline(title, interpretation, modules, skipped);
        cache.put(cacheKey, outline);
        return outline;
    }

    /**
     * A FLAT roadmap for a small goal (Phase 18) — one ordered step list drafted directly, no
     * modules, used when the assessment judges the goal too small to need named areas.
     * {@code null} on failure.
     */
    public FlatProposal proposeFlat(String goal, String clarifications, String profileContext,
                                    String groundingContext, String assessmentContext,
                                    String domain) {
        String cacheKey = AiGenerationCache.key("flat", goal, clarifications, profileContext,
                groundingContext, assessmentContext, domain);
        FlatProposal cached = cache.get(cacheKey);
        if (cached != null) {
            return cached;
        }
        JsonNode json = ai.generate(AiTier.HEAVY, "flat roadmap", PromptTemplates.FLAT_PROPOSE_SYSTEM,
                PromptTemplates.flatProposeUser(goal, clarifications, profileContext,
                        groundingContext, assessmentContext, domain));
        if (json == null) {
            return null;
        }
        String title = AiJsonGenerator.text(json.get("title"));
        String interpretation = AiJsonGenerator.text(json.get("interpretation"));
        List<DraftStep> steps = parseSteps(json.get("steps"), Set.of());
        if (steps.isEmpty()) {
            return null;
        }
        List<String> skipped = AiJsonGenerator.strings(json.get("skipped"));
        FlatProposal proposal = new FlatProposal(title, interpretation, steps, skipped);
        cache.put(cacheKey, proposal);
        return proposal;
    }

    /**
     * Expand ONE module of a roadmap into its ordered steps (Phase 13), scoped to that module.
     * {@code priorSteps} (Phase 18) are already-expanded steps from EARLIER modules, with their
     * real entry ids, so a step here can name a genuine cross-module prerequisite instead of only
     * ones inside this same batch. {@code null} on failure.
     */
    public List<DraftStep> expandModule(String roadmapTitle, String moduleTitle, String moduleScope,
                                        String profileContext, String groundingContext,
                                        String assessmentContext, List<PriorStep> priorSteps,
                                        boolean isFoundationalModule, String domain) {
        String priorStepsKey = priorSteps == null ? "" : priorSteps.stream()
                .map(p -> p.id() + ":" + p.text()).collect(java.util.stream.Collectors.joining("|"));
        String cacheKey = AiGenerationCache.key("expand", roadmapTitle, moduleTitle, moduleScope,
                profileContext, groundingContext, assessmentContext, priorStepsKey,
                String.valueOf(isFoundationalModule), domain);
        List<DraftStep> cached = cache.get(cacheKey);
        if (cached != null) {
            return cached;
        }
        JsonNode json = ai.generate(AiTier.HEAVY, "module expansion", PromptTemplates.EXPAND_MODULE_SYSTEM,
                PromptTemplates.expandModuleUser(roadmapTitle, moduleTitle, moduleScope,
                        profileContext, groundingContext, assessmentContext, priorSteps,
                        isFoundationalModule, domain));
        if (json == null) {
            return null;
        }
        Set<Long> priorIds = priorSteps == null ? Set.of()
                : priorSteps.stream().map(PriorStep::id).collect(java.util.stream.Collectors.toSet());
        List<DraftStep> steps = parseSteps(json.get("steps"), priorIds);
        if (steps.isEmpty()) {
            return null;
        }
        cache.put(cacheKey, steps);
        return steps;
    }

    /**
     * Redraft one module's title/scope in place (Phase 18) — "regenerate this module" when the
     * outline is right but one area isn't. {@code null} on failure.
     */
    public OutlineModule regenerateModuleScope(String roadmapTitle, String moduleTitle,
                                               String currentScope, String siblingModulesContext) {
        JsonNode json = ai.generate(AiTier.HEAVY, "module scope regeneration", PromptTemplates.REGENERATE_MODULE_SYSTEM,
                PromptTemplates.regenerateModuleUser(roadmapTitle, moduleTitle, currentScope,
                        siblingModulesContext));
        return oneModule(json);
    }

    /**
     * Draft ONE new module to insert into an existing outline (Phase 18) — "insert a module
     * here" when the user notices a real gap. {@code null} on failure.
     */
    public OutlineModule proposeModule(String roadmapTitle, String existingModulesContext,
                                       String assessmentContext) {
        JsonNode json = ai.generate(AiTier.HEAVY, "module insertion", PromptTemplates.INSERT_MODULE_SYSTEM,
                PromptTemplates.insertModuleUser(roadmapTitle, existingModulesContext, assessmentContext));
        return oneModule(json);
    }

    /**
     * Redraft every not-yet-expanded module given real progress so far (Phase 18) — "replan
     * remaining modules." Returns one redraft per remaining module, in order; {@code null} on
     * failure, or if the model didn't return the same count it was given (a restructure it was
     * told not to do by default — safer to surface nothing than misalign ids to modules).
     */
    public List<OutlineModule> replanModules(String roadmapTitle, String doneModulesContext,
                                             String remainingModulesContext, String assessmentContext,
                                             int expectedCount) {
        JsonNode json = ai.generate(AiTier.HEAVY, "outline replan", PromptTemplates.REPLAN_SYSTEM,
                PromptTemplates.replanUser(roadmapTitle, doneModulesContext, remainingModulesContext,
                        assessmentContext));
        if (json == null) {
            return null;
        }
        List<OutlineModule> modules = parseModules(json.get("modules"));
        return modules.size() == expectedCount ? modules : null;
    }

    private static OutlineModule oneModule(JsonNode json) {
        if (json == null) {
            return null;
        }
        String title = AiJsonGenerator.text(json.get("title"));
        if (title == null || title.isBlank()) {
            return null;
        }
        String scope = AiJsonGenerator.text(json.get("scope"));
        return new OutlineModule(title.trim(), scope == null ? null : scope.trim());
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

    /** 2–4 short bullets of what a roadmap step covers, or {@code null} on failure (Phase 7.5). */
    public List<String> stepCovers(String roadmapTitle, String stepText) {
        JsonNode json = ai.generate(AiTier.FAST, "step covers",
                PromptTemplates.COVERS_SYSTEM, PromptTemplates.coversUser(roadmapTitle, stepText));
        if (json == null) {
            return null;
        }
        List<String> covers = AiJsonGenerator.strings(json.get("covers"));
        return covers.isEmpty() ? null : covers;
    }

    /**
     * Parse and sanitize the structured step array; skips entries without real text.
     * {@code validPriorIds} (Phase 18) are the real ids offered as cross-module prerequisites —
     * a {@code dependsOnEntryId} not in this set is dropped rather than trusted blindly.
     */
    private static List<DraftStep> parseSteps(JsonNode array, Set<Long> validPriorIds) {
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
            JsonNode indexNode = node.get("dependsOnIndex") != null ? node.get("dependsOnIndex") : node.get("dependsOn");
            Integer dependsOn = indexNode != null && indexNode.isInt() ? indexNode.asInt() : null;
            JsonNode entryIdNode = node.get("dependsOnEntryId");
            Long dependsOnEntryId = entryIdNode != null && entryIdNode.isIntegralNumber()
                    ? entryIdNode.asLong() : null;
            if (dependsOnEntryId != null && !validPriorIds.contains(dependsOnEntryId)) {
                dependsOnEntryId = null; // only ids we actually offered are real
            }
            // Mutually exclusive — a cross-module id takes priority if the model set both.
            if (dependsOnEntryId != null) {
                dependsOn = null;
            }
            String rationale = AiJsonGenerator.text(node.get("rationale"));
            JsonNode riskNode = node.get("riskScore");
            Integer riskScore = riskNode != null && riskNode.isInt()
                    ? Math.max(1, Math.min(5, riskNode.asInt())) : null;
            // Only meaningful when a dependency is actually set — drop stray scores otherwise.
            if (dependsOn == null && dependsOnEntryId == null) {
                riskScore = null;
            }
            steps.add(new DraftStep(text.trim(), kind, weight, dependsOn, dependsOnEntryId,
                    rationale == null ? null : rationale.trim(), riskScore));
        }
        // A same-batch dependsOn index is only valid if it points at an earlier step in THIS
        // batch; drop anything else. A cross-module dependsOnEntryId was already validated above.
        List<DraftStep> validated = new ArrayList<>();
        for (int i = 0; i < steps.size(); i++) {
            DraftStep s = steps.get(i);
            Integer dep = s.dependsOn() != null && s.dependsOn() >= 0 && s.dependsOn() < i
                    ? s.dependsOn() : null;
            validated.add(new DraftStep(s.text(), s.kind(), s.weight(), dep,
                    s.dependsOnEntryId(), s.rationale(), s.riskScore()));
        }
        return validated;
    }

    private static String valueIn(String value, Set<String> allowed, String fallback) {
        return value != null && allowed.contains(value) ? value : fallback;
    }

    /**
     * The emergency skeleton path (Phase 19): step titles only for one module, tried against the
     * FAST tier when the HEAVY tier's whole chain has already failed a real {@link #expandModule}
     * call. {@code null} when even this smaller ask can't be served — at that point nothing more
     * can be done without a working provider.
     */
    public List<String> skeletonModuleSteps(String roadmapTitle, String moduleTitle, String moduleScope) {
        JsonNode json = ai.generateSkeleton("module skeleton",
                PromptTemplates.SKELETON_EXPAND_SYSTEM,
                PromptTemplates.skeletonExpandUser(roadmapTitle, moduleTitle, moduleScope));
        if (json == null) {
            return null;
        }
        List<String> titles = AiJsonGenerator.strings(json.get("steps"));
        return titles.isEmpty() ? null : titles;
    }

    /**
     * Smaller sub-steps that replace one stalled step (Phase 20) — same richer shape as
     * {@link #expandModule}, not plain text, so kind/weight/rationale/resources survive a
     * break-down instead of it reading as a visibly poorer result than every other generation
     * path. {@code null} on failure.
     */
    public List<DraftStep> breakDownStep(String roadmapTitle, String stepText, String profileContext,
                                         String groundingContext, String domain) {
        JsonNode json = ai.generate(AiTier.HEAVY, "step breakdown", PromptTemplates.BREAKDOWN_SYSTEM,
                PromptTemplates.breakdownUser(roadmapTitle, stepText, profileContext, groundingContext, domain));
        if (json == null) {
            return null;
        }
        List<DraftStep> steps = parseSteps(json.get("steps"), Set.of());
        return steps.isEmpty() ? null : steps;
    }

    /**
     * A single prerequisite step to insert before a stalled step, or {@code null} when
     * unavailable, both providers fail, or the model judges nothing is genuinely missing.
     * {@code gapHint} (Phase 20, nullable) is a specific named gap from a failed verification
     * check — real evidence weighed heavily, not just the step's own text to guess from.
     */
    public Prerequisite proposePrerequisite(String roadmapTitle, String stepText, String priorSteps,
                                            String gapHint) {
        JsonNode json = ai.generate(AiTier.HEAVY, "prerequisite proposal", PromptTemplates.PREREQUISITE_SYSTEM,
                PromptTemplates.prerequisiteUser(roadmapTitle, stepText, priorSteps, gapHint));
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
     * A short checkpoint step connecting a cross-module prerequisite to a step scored as a real
     * conceptual leap (Phase 20) — cheap enough for the fast tier, since it's one small step, not
     * a redraft. {@code null} on failure; the caller falls back to no bridge rather than blocking.
     */
    public String bridgeStep(String priorStepText, String nextStepText) {
        JsonNode json = ai.generate(AiTier.FAST, "bridge step", PromptTemplates.BRIDGE_STEP_SYSTEM,
                PromptTemplates.bridgeStepUser(priorStepText, nextStepText));
        if (json == null) {
            return null;
        }
        String step = AiJsonGenerator.text(json.get("step"));
        return step == null || step.isBlank() ? null : step.trim();
    }

    /**
     * A cheap self-consistency pass over a just-drafted step list (Phase 20) — ordering, clarity,
     * missing prerequisites, technical accuracy, gaps against scope. {@code heavy} routes to the
     * heavy tier for a deeper pass on career-scale roadmaps; otherwise the fast tier, since this
     * is a lightweight second look, not a redraft. Empty list on failure or when nothing's
     * flagged — best-effort and never blocks the draft it's reviewing.
     */
    public List<CritiqueIssue> critique(String goal, String scope, List<String> stepTexts, boolean heavy) {
        AiTier tier = heavy ? AiTier.HEAVY : AiTier.FAST;
        JsonNode json = ai.generate(tier, "self-critique", PromptTemplates.CRITIQUE_SYSTEM,
                PromptTemplates.critiqueUser(goal, scope, stepTexts));
        if (json == null || json.get("issues") == null || !json.get("issues").isArray()) {
            return List.of();
        }
        List<CritiqueIssue> issues = new ArrayList<>();
        for (JsonNode node : json.get("issues")) {
            String message = AiJsonGenerator.text(node.get("message"));
            if (message == null || message.isBlank()) {
                continue;
            }
            String severity = valueIn(AiJsonGenerator.text(node.get("severity")), SEVERITIES, "low")
                    .toUpperCase(java.util.Locale.ROOT);
            JsonNode indexNode = node.get("stepIndex");
            Integer stepIndex = indexNode != null && indexNode.isInt()
                    && indexNode.asInt() >= 0 && indexNode.asInt() < stepTexts.size()
                    ? indexNode.asInt() : null;
            String suggestedFix = stepIndex == null ? null : AiJsonGenerator.text(node.get("suggestedFix"));
            issues.add(new CritiqueIssue(severity, message.trim(),
                    stepIndex, suggestedFix == null || suggestedFix.isBlank() ? null : suggestedFix.trim()));
        }
        return issues;
    }

    private static final Set<String> SEVERITIES = Set.of("high", "medium", "low");

    /**
     * One flagged issue from a self-critique pass (Phase 20). {@code stepIndex} is the 0-based
     * index (into the same batch) the issue is about, or null for a plan-wide issue.
     * {@code suggestedFix} is a concrete replacement for that step's text — set only for a
     * wording/clarity issue with an unambiguous fix, null otherwise.
     */
    public record CritiqueIssue(String severity, String message, Integer stepIndex, String suggestedFix) {
    }

    /**
     * One proposed step. {@code kind} is concept|project, {@code weight} is small|medium|large,
     * {@code dependsOn} is the 0-based index of an earlier prerequisite step in this same batch
     * (or null), {@code dependsOnEntryId} (Phase 18) is the real id of a prerequisite step from an
     * EARLIER module instead (or null — at most one of the two is ever set), {@code rationale}
     * says why it's here / why the prerequisite comes first, and {@code riskScore} (Phase 20,
     * 1-5, nullable) is the model's read of how big a conceptual leap the dependency represents —
     * only meaningful when a dependency is actually set. A cross-module dependency scored 4+ gets
     * an auto-generated bridge step (see {@link RoadmapService}).
     */
    public record DraftStep(String text, String kind, String weight, Integer dependsOn,
                            Long dependsOnEntryId, String rationale, Integer riskScore) {
        public DraftStep(String text, String kind, String weight, Integer dependsOn,
                         Long dependsOnEntryId, String rationale) {
            this(text, kind, weight, dependsOn, dependsOnEntryId, rationale, null);
        }
    }

    /** A proposed prerequisite step plus the one-line reason it comes first. */
    public record Prerequisite(String step, String why) {
    }

    /**
     * A shared, structured read of a goal's scope (Phase 18): {@code complexity} is 1-5,
     * {@code estimatedTotalHours} is a best-effort estimate (nullable), {@code domain} and
     * {@code priorLevel} are a couple of words each, and {@code shape} is strictly "flat" or
     * "nested" — the gate between a single step list and a modules-then-steps roadmap.
     *
     * <p>{@code archetype} (Phase 24) is a coarse classification — "quick_task",
     * "topic_deep_dive", or "career_path" — additive to this same call. It exists because
     * {@code complexity}/{@code shape} alone can't tell a deep topic dive apart from a career
     * pivot at the same complexity/shape; {@code career_path} is what gates the Phase 24
     * portfolio-arc bias and Project Portfolio Mandate. Never null from a successful assessment;
     * defaults to "topic_deep_dive" if the model's value doesn't match a known one.
     */
    public record GoalAssessment(int complexity, Integer estimatedTotalHours, String domain,
                                 String priorLevel, String shape, String archetype) {
    }

    /**
     * A drafted FLAT roadmap (Phase 18): one ordered step list for a goal small enough not to
     * need named modules. Same shape as {@link RoadmapOutline} otherwise — a title, an optional
     * stated interpretation/assumptions, and any topics skipped based on the profile.
     */
    public record FlatProposal(String title, String interpretation, List<DraftStep> steps,
                               List<String> skipped) {
    }

    /** One already-expanded step from an earlier module, offered as a real cross-module id. */
    public record PriorStep(Long id, String text) {
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
}
