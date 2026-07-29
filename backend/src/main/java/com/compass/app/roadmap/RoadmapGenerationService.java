package com.compass.app.roadmap;

import com.compass.app.ai.AiVoiceService;
import com.compass.app.ai.ResourceAiService;
import com.compass.app.ai.RoadmapAiService;
import com.compass.app.ai.SearchGroundingService;
import com.compass.app.ai.Tier;
import com.compass.app.entry.Entry;
import com.compass.app.entry.EntryRepository;
import com.compass.app.entry.EntryService;
import com.compass.app.entry.EntryStatus;
import com.compass.app.entry.EntryType;
import com.compass.app.entry.dto.CreateEntryRequest;
import com.compass.app.events.EventService;
import com.compass.app.profile.ProfileContext;
import com.compass.app.profile.ProfileService;
import com.compass.app.resource.ResourceService;
import com.compass.app.roadmap.dto.CreateRoadmapRequest;
import com.compass.app.roadmap.dto.GenerateRoadmapRequest;
import com.compass.app.roadmap.dto.GenerateRoadmapResponse;
import com.compass.app.roadmap.dto.ModuleExpansionResult;
import com.compass.app.roadmap.dto.ReplanModuleItem;
import com.compass.app.topic.TopicMatcherService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;

/**
 * The AI-facing drafting/expansion pipeline (V3-4.1's split of the former {@code RoadmapService}
 * god-file — see {@code TASKS_v3.md}): every method that makes an AI call to draft, expand,
 * redraft, or critique roadmap content. Pure move, no behaviour change — {@link
 * com.compass.app.roadmap.RoadmapServiceBehaviorTest}'s coverage of these methods moved with them
 * into {@code RoadmapGenerationServiceTest}. The last of the four extracted seams — {@link
 * RoadmapQueryService}, {@link RoadmapRetierService}, and {@link RoadmapStructureService} came
 * first, in that order, each least-risky-first.
 */
@Service
public class RoadmapGenerationService {

    private final EntryRepository repository;
    private final RoadmapQueryService queryService;
    private final RoadmapStructureService structureService;
    private final RoadmapAiService roadmapAi;
    private final ProfileService profileService;
    private final SearchGroundingService searchGrounding;
    private final ResourceService resourceService;
    private final EntryService entryService;
    private final AiVoiceService aiVoice;
    private final EventService events;
    private final TopicMatcherService topicMatcher;
    // Search results arrive relevance-ordered; only the top few are worth spending prompt tokens
    // on (Phase 19) — smaller prompts are faster and cheaper on every provider, especially the
    // slowest tier. Resource discovery separately reads the full uncapped result set.
    private final int maxGroundingSnippets;
    // A TASK classification below this confidence falls through to the normal roadmap pipeline
    // instead of short-circuiting (RB-2.2) — a wrongly-skipped roadmap is a worse failure than an
    // unnecessary small roadmap drafted for something that was actually just a task.
    private static final double TASK_ROUTE_CONFIDENCE = 0.75;
    // Bounds concurrency for expandModulesBatch (Phase 19) so a batch of many modules doesn't
    // trip rate limits across the whole provider chain at once. Owned by AsyncConfig now, not
    // created here, so Spring drains it on shutdown instead of leaving threads mid-AI-call.
    private final ExecutorService expansionExecutor;

    public RoadmapGenerationService(EntryRepository repository, RoadmapQueryService queryService,
                                    RoadmapStructureService structureService, RoadmapAiService roadmapAi,
                                    ProfileService profileService, SearchGroundingService searchGrounding,
                                    ResourceService resourceService, EntryService entryService,
                                    AiVoiceService aiVoice, EventService events,
                                    TopicMatcherService topicMatcher, ExecutorService expansionExecutor,
                                    @org.springframework.beans.factory.annotation.Value(
                                            "${compass.search.max-context-snippets:5}") int maxGroundingSnippets) {
        this.repository = repository;
        this.queryService = queryService;
        this.structureService = structureService;
        this.roadmapAi = roadmapAi;
        this.profileService = profileService;
        this.searchGrounding = searchGrounding;
        this.resourceService = resourceService;
        this.entryService = entryService;
        this.aiVoice = aiVoice;
        this.events = events;
        this.topicMatcher = topicMatcher;
        this.expansionExecutor = expansionExecutor;
        this.maxGroundingSnippets = maxGroundingSnippets;
    }

    /**
     * One turn of the AI drafting flow (Phase 4, reshaped by Phases 13 and 17). Up to three
     * turns end to end:
     * <ol>
     *   <li>{@code clarifications == null} → ask 0–4 goal-specific questions (adaptive, no fixed
     *       default pair); if the model genuinely has nothing to ask, draft immediately instead
     *       of returning an empty question form;</li>
     *   <li>{@code clarifications} present, {@code skipFollowUp == false} → check for one
     *       genuine follow-up round conditioned on those answers; a real follow-up comes back as
     *       another {@code needs_clarification}, otherwise falls through to drafting;</li>
     *   <li>{@code skipFollowUp == true} (or no follow-up was found) → draft the top-level
     *       MODULE OUTLINE — not individual steps. Each module is expanded into its own steps
     *       later, on demand, via {@link #expandModule}.</li>
     * </ol>
     * Nothing is persisted until the user creates the roadmap the normal way. Throws
     * {@link IllegalStateException} when no AI provider can serve the request, so the caller can
     * fall back to writing steps by hand.
     */
    public GenerateRoadmapResponse generate(GenerateRoadmapRequest req) {
        return generate(req, stage -> { });
    }

    /**
     * As {@link #generate(GenerateRoadmapRequest)}, but reports which {@link GenerationStage} is
     * currently running via {@code onStage} (Phase 18) — used by {@link GenerationWorker} so
     * a slow AI call (the free-tier tertiary provider can take up to a minute) shows live progress
     * instead of a frozen wait. Called with a no-op consumer by the synchronous overload above.
     */
    GenerateRoadmapResponse generate(GenerateRoadmapRequest req, java.util.function.Consumer<GenerationStage> onStage) {
        String goal = req.goal() != null ? req.goal().trim() : "";
        if (goal.isEmpty()) {
            throw new IllegalArgumentException("Say what you want a roadmap for first.");
        }
        if (!roadmapAi.isAvailable()) {
            throw new IllegalStateException(
                    "Drafting is unavailable right now — write the steps yourself.");
        }

        // Only a confirmed profile feeds generation (CLAUDE.md); null when none/unconfirmed.
        String profileContext = profileService.confirmedProfile()
                .map(ProfileContext::forPrompt)
                .orElse(null);

        // RB-2.1: classify BEFORE anything else runs — never after, never in parallel — so a
        // TASK-tier goal never triggers clarifying questions in the first place. Only on the very
        // first turn (clarifications == null); later turns echo back req.tier() instead of paying
        // for a second classification call. A failed classification (null) isn't fatal — it just
        // means no tier gets stored and every goal continues through today's unchanged pipeline,
        // same as any other best-effort AI signal in this codebase.
        String tier = req.tier();
        if (req.clarifications() == null) {
            RoadmapAiService.TierClassification classification = roadmapAi.classifyTier(goal, profileContext);
            if (classification != null) {
                tier = classification.tier().name();
                logClassification(classification);
                if (classification.tier() == Tier.TASK && classification.confidence() >= TASK_ROUTE_CONFIDENCE) {
                    return routeToTask(goal);
                }
            }

            // RB-3.5: right after RB-2's size classification confirms non-TASK, before clarifying
            // questions — same short-circuit-early reasoning as RB-2. Skipped on the founder's
            // explicit say-so (already saw this match, chose to proceed as new) or when the
            // matcher itself can't run (no embeddings configured, or no topics stored yet) —
            // either way, falls through to today's unchanged pipeline rather than blocking on it.
            if (!req.skipTopicMatch()) {
                TopicMatcherService.MatchResult match = topicMatcher.match(goal);
                if (match != null && !"new".equals(match.matchType())) {
                    return GenerateRoadmapResponse.topicMatch(new GenerateRoadmapResponse.TopicMatch(
                            match.matchType(), match.confidence(), match.reasoning(),
                            match.topic().getId(), match.topic().getCanonicalName(),
                            match.topic().getRoadmapEntryId(), match.topic().getSubtopics()))
                            .withTier(tier);
                }
            }

            onStage.accept(GenerationStage.CLARIFYING);
            List<String> questions = roadmapAi.clarifyingQuestions(goal, profileContext);
            if (questions == null) {
                throw new IllegalStateException(
                        "Drafting is unavailable right now — write the steps yourself.");
            }
            if (questions.isEmpty()) {
                // Nothing genuinely worth asking — draft straight away rather than showing an
                // empty question form; the outline prompt states its assumptions plainly instead.
                return draft(goal, "", profileContext, onStage, tier);
            }
            return GenerateRoadmapResponse.needsClarification(questions).withTier(tier);
        }

        String firstRoundQa = formatClarifications(req.clarifications());
        if (!req.skipFollowUp()) {
            onStage.accept(GenerationStage.CLARIFYING);
            List<String> followUps = roadmapAi.followUpQuestions(goal, firstRoundQa, profileContext);
            // followUps == null means the follow-up check itself failed (unavailable/error) —
            // treat that the same as "nothing to add" rather than blocking drafting on it.
            if (followUps != null && !followUps.isEmpty()) {
                return GenerateRoadmapResponse.needsClarification(followUps).withTier(tier);
            }
        }

        return draft(goal, firstRoundQa, profileContext, onStage, tier);
    }

    /**
     * TASK-tier routing (RB-2.2): skip the roadmap pipeline entirely and reuse the existing
     * direct-capture task path — same entry creation, same self-talk-voice acknowledgment, as if
     * the founder had captured the goal as a task themselves.
     */
    private GenerateRoadmapResponse routeToTask(String goal) {
        Entry task = entryService.create(new CreateEntryRequest(EntryType.TASK, goal, null, null, null, null));
        String ack = aiVoice.acknowledge(task);
        return GenerateRoadmapResponse.routedToTask(task.getId(), goal, ack);
    }

    /**
     * Brief system_events entry per classification (RB-2.4) — what makes the lighter,
     * spot-check-only testing approach actually workable going forward: a glance at
     * {@code /admin/events} shows real classification behavior over time without re-running the
     * full RB-1 battery.
     */
    private void logClassification(RoadmapAiService.TierClassification c) {
        String outcome = c.tier() == Tier.TASK && c.confidence() >= TASK_ROUTE_CONFIDENCE
                ? "routed to task entry" : "continuing to roadmap pipeline";
        events.info("tier_classification", "goal classified as " + c.tier()
                + " (" + String.format(java.util.Locale.ROOT, "%.2f", c.confidence()) + ") — " + outcome, null);
    }

    /**
     * The actual drafting call (Phase 18), shared by the zero-questions and answered-questions
     * paths. Grounds once, assesses the goal's scope once, then gates on {@code shape}: a small
     * goal drafts straight to a flat step list; a bigger one drafts the module outline as before.
     * A failed assessment falls back to today's un-assessed behavior (nested, mid-range) rather
     * than blocking generation on it.
     */
    private GenerateRoadmapResponse draft(String goal, String clarificationsText, String profileContext,
                                          java.util.function.Consumer<GenerationStage> onStage, String tier) {
        // Ground once, in real sources when a search key is configured; null (and no sources)
        // when it isn't, and generation proceeds ungrounded. Shared by assessment and drafting.
        SearchGroundingService.Grounding grounding = searchGrounding.ground(goal);
        String groundingContext = grounding == null ? null : grounding.contextTop(maxGroundingSnippets);
        List<String> sources = grounding == null ? List.of() : grounding.sources();

        onStage.accept(GenerationStage.ASSESSING);
        RoadmapAiService.GoalAssessment assessment = roadmapAi.assessGoal(
                goal, clarificationsText, profileContext, groundingContext);
        if (assessment == null) {
            assessment = new RoadmapAiService.GoalAssessment(3, null, null, null, "nested", "topic_deep_dive");
        }
        assessment = reconcileShapeWithTier(assessment, tier);
        String assessmentContext = RoadmapAiService.assessmentContext(assessment);

        if ("flat".equals(assessment.shape())) {
            onStage.accept(GenerationStage.DRAFTING);
            RoadmapAiService.FlatProposal flat = roadmapAi.proposeFlat(
                    goal, clarificationsText, profileContext, groundingContext, assessmentContext,
                    assessment.domain());
            if (flat == null) {
                throw new IllegalStateException(
                        "Drafting is unavailable right now — write the steps yourself.");
            }
            // Resources are no longer drafted here (see ResourceController/ResourceService) —
            // the founder reviews the step structure immediately, and the frontend fetches
            // resources as a quick follow-up call while the proposal is still open for review.
            List<String> stepTexts = flat.steps().stream().map(RoadmapAiService.DraftStep::text).toList();
            List<List<ResourceAiService.Resource>> noResources = stepTexts.stream()
                    .map(t -> List.<ResourceAiService.Resource>of()).toList();
            List<RoadmapAiService.CritiqueIssue> issues =
                    critiqueIfWarranted(goal, null, stepTexts, assessment.complexity(), tier);
            return GenerateRoadmapResponse.proposal(flat.title(), flat.interpretation(), flat.steps(),
                    noResources, flat.skipped(), sources, assessment, Map.of(), false, issues)
                    .withTier(tier);
        }

        onStage.accept(GenerationStage.DRAFTING);
        RoadmapAiService.RoadmapOutline outline = roadmapAi.moduleOutline(
                goal, clarificationsText, profileContext, groundingContext, assessmentContext,
                assessment.domain(), tier);
        if (outline == null) {
            throw new IllegalStateException(
                    "Drafting is unavailable right now — write the steps yourself.");
        }
        return GenerateRoadmapResponse.outline(outline.title(), outline.interpretation(),
                outline.modules(), outline.skipped(), sources, assessment)
                .withTier(tier);
    }

    /**
     * RB-4.1: once RB-2's classifier confidently placed a goal in a tier, {@code shape} is
     * DERIVED from it rather than independently re-guessed — MINI is flat, TOPIC/CAREER are
     * nested. A {@code null} tier (classification failed, or never ran) keeps today's
     * independently-assessed shape as the fallback, unchanged.
     */
    static RoadmapAiService.GoalAssessment reconcileShapeWithTier(
            RoadmapAiService.GoalAssessment assessment, String tier) {
        if (tier == null) {
            return assessment;
        }
        String derivedShape = "MINI".equals(tier) ? "flat"
                : ("TOPIC".equals(tier) || "CAREER".equals(tier)) ? "nested" : null;
        if (derivedShape == null || derivedShape.equals(assessment.shape())) {
            return assessment;
        }
        return new RoadmapAiService.GoalAssessment(assessment.complexity(), assessment.estimatedTotalHours(),
                assessment.domain(), assessment.priorLevel(), derivedShape, assessment.archetype());
    }

    /**
     * Expand more than one module at once (Phase 19), only on the founder's explicit request —
     * the existing "expand on demand" single-module flow (JIT, quota-conscious per Phase 13)
     * stays the silent default. Each module's expansion runs independently via
     * {@link #expandModule}, concurrently rather than sequentially. One module failing doesn't
     * affect the others — each result records its own success or error.
     */
    public List<ModuleExpansionResult> expandModulesBatch(Long roadmapId, List<Long> moduleIds) {
        List<CompletableFuture<ModuleExpansionResult>> futures = moduleIds.stream()
                .map(moduleId -> CompletableFuture.supplyAsync(
                        () -> expandOneForBatch(roadmapId, moduleId), expansionExecutor))
                .toList();
        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
        return futures.stream().map(CompletableFuture::join).toList();
    }

    private ModuleExpansionResult expandOneForBatch(Long roadmapId, Long moduleId) {
        try {
            return new ModuleExpansionResult(moduleId, expandModule(roadmapId, moduleId), null);
        } catch (RuntimeException ex) {
            return new ModuleExpansionResult(moduleId, null, ex.getMessage());
        }
    }

    /**
     * Expand one module of a roadmap into its own proposed steps (Phase 13), grounded on that
     * module's own title/scope (not the whole goal) so search stays relevant and resources stay
     * scoped. Resources also exclude every url already used elsewhere in this roadmap, so the
     * same link never appears on two steps. Nothing is persisted — accept via {@code
     * addStepsToModule}. Throws {@link IllegalStateException} when drafting fails.
     */
    @Transactional(readOnly = true)
    public GenerateRoadmapResponse expandModule(Long roadmapId, Long moduleId) {
        Entry roadmap = queryService.getRoadmap(roadmapId);
        Entry module = queryService.requireModule(roadmapId, moduleId);
        if (!roadmapAi.isAvailable()) {
            throw new IllegalStateException(
                    "Drafting is unavailable right now — write its steps yourself.");
        }

        GenerateRoadmapResponse full = attemptFullExpand(roadmapId, roadmap, module);
        if (full != null) {
            return full;
        }

        // The whole heavy chain failed (Phase 19) — try the much smaller titles-only ask against
        // the fast tier before giving up entirely. A degraded result the founder can see (and a
        // background retry can later fill in) beats a flat "unavailable."
        String roadmapTitle = stringOf(roadmap, "title");
        String moduleTitle = stringOf(module, "title");
        String moduleScope = stringOf(module, "scope");
        List<String> skeletonTitles = roadmapAi.skeletonModuleSteps(roadmapTitle, moduleTitle, moduleScope);
        if (skeletonTitles == null) {
            throw new IllegalStateException(
                    "Couldn't draft this module right now — write its steps yourself.");
        }
        List<RoadmapAiService.DraftStep> skeletonSteps = skeletonTitles.stream()
                .map(text -> new RoadmapAiService.DraftStep(text, "concept", "medium", null, null, null))
                .toList();
        List<List<ResourceAiService.Resource>> noResources = skeletonSteps.stream()
                .map(s -> List.<ResourceAiService.Resource>of()).toList();
        return GenerateRoadmapResponse.proposal(moduleTitle, null, skeletonSteps, noResources,
                List.of(), List.of(), null, Map.of(), true);
    }

    /**
     * The real drafting attempt shared by {@link #expandModule} and the skeleton background
     * retry (Phase 19): full context gathering plus the heavy-tier AI call. {@code null} means
     * the whole heavy chain failed — the caller decides what degraded path to take next.
     */
    private GenerateRoadmapResponse attemptFullExpand(Long roadmapId, Entry roadmap, Entry module) {
        String roadmapTitle = stringOf(roadmap, "title");
        String moduleTitle = stringOf(module, "title");
        String moduleScope = stringOf(module, "scope");
        // Pruned to what's relevant to THIS module's topic (Phase 19), not the founder's whole
        // profile every time — see ProfileContext.forModulePrompt.
        String profileContext = profileService.confirmedProfile()
                .map(p -> ProfileContext.forModulePrompt(p, moduleTitle, moduleScope))
                .orElse(null);
        String assessmentContext = storedAssessmentContext(roadmap);
        String domain = storedDomain(roadmap);

        // Steps already drafted in genuinely earlier modules (lower order index), offered as
        // real cross-module prerequisites (Phase 18) — not just same-batch ones.
        List<RoadmapAiService.PriorStep> priorSteps = new ArrayList<>();
        Map<Long, String> priorStepTextById = new HashMap<>();
        if (module.getOrderIndex() != null) {
            for (Entry sibling : repository.findByParentIdOrderByOrderIndexAsc(roadmapId)) {
                if (sibling.getOrderIndex() == null || sibling.getOrderIndex() >= module.getOrderIndex()) {
                    continue;
                }
                for (Entry step : repository.findByParentIdOrderByOrderIndexAsc(sibling.getId())) {
                    if (step.getType() != EntryType.ROADMAP_STEP) {
                        continue;
                    }
                    String text = stringOf(step, "text");
                    if (text == null || text.isBlank()) {
                        continue;
                    }
                    priorSteps.add(new RoadmapAiService.PriorStep(step.getId(), text));
                    priorStepTextById.put(step.getId(), text);
                }
            }
        }

        String groundingQuery = moduleScope != null && !moduleScope.isBlank()
                ? moduleTitle + ": " + moduleScope : moduleTitle;
        // A few differently-framed queries about the same module (Phase 20) surface a broader,
        // more varied set of real sources than one query alone — official docs, hands-on ideas,
        // and common beginner pitfalls each tend to turn up different results. Each query is
        // still cheap via the existing TTL cache; groundMulti just dedupes the merged results.
        SearchGroundingService.Grounding grounding = searchGrounding.groundMulti(List.of(
                groundingQuery,
                moduleTitle + " official documentation",
                moduleTitle + " common mistakes beginners make"));
        String groundingContext = grounding == null ? null : grounding.contextTop(maxGroundingSnippets);
        List<String> sources = grounding == null ? List.of() : grounding.sources();

        // Foundational (first) module gets no portfolio-project expectation even for a
        // career-scale roadmap — the mandate only applies once past foundations (Phase 24).
        boolean isFoundationalModule = module.getOrderIndex() == null || module.getOrderIndex() == 0;
        List<RoadmapAiService.DraftStep> steps = roadmapAi.expandModule(roadmapTitle, moduleTitle,
                moduleScope, profileContext, groundingContext, assessmentContext, priorSteps,
                isFoundationalModule, domain);
        if (steps == null) {
            return null;
        }
        steps = withBridgeSteps(steps, priorStepTextById);
        List<String> stepTexts = steps.stream().map(RoadmapAiService.DraftStep::text).toList();
        // Module expansion keeps its existing combined steps+resources timing (already solved by
        // background prefetching — see ModulePrefetchService), unlike the other drafting paths.
        List<List<ResourceAiService.Resource>> resources = resourceService.suggestResourcesPerStep(
                moduleTitle, stepTexts, grounding == null ? null : grounding.results(), roadmapId);
        List<RoadmapAiService.CritiqueIssue> issues = critiqueIfWarranted(
                roadmapTitle, moduleScope, stepTexts, storedComplexity(roadmap), storedTier(roadmap));
        return GenerateRoadmapResponse.proposal(moduleTitle, null, steps, resources, List.of(),
                sources, null, priorStepTextById, false, issues);
    }

    // A cross-module dependency scored this risky or higher gets an auto-generated bridge step
    // (Phase 20) — deliberately only cross-module, not same-batch, per TASKS.md: the conceptual
    // gap is most likely to be real across modules, and scoring every same-batch pair would
    // generate a bridge step between nearly every two steps in a module.
    private static final int BRIDGE_RISK_THRESHOLD = 4;

    /**
     * Insert an auto-generated "bridge step" (Phase 20) immediately before any step whose
     * cross-module dependency was scored a real conceptual leap ({@code riskScore >= 4}) —
     * connecting the earlier module's concept to this one. Same propose→approve→apply pattern as
     * everything else: nothing is persisted here, and the founder can remove the bridge step from
     * the proposal before accepting like any other step. Falls back to leaving the step
     * unchanged if the (cheap, fast-tier) bridge-step call fails — a missing bridge isn't worth
     * blocking the whole module draft over.
     */
    private List<RoadmapAiService.DraftStep> withBridgeSteps(
            List<RoadmapAiService.DraftStep> steps, Map<Long, String> priorStepTextById) {
        List<RoadmapAiService.DraftStep> result = new ArrayList<>();
        int[] oldToNew = new int[steps.size()];
        for (int i = 0; i < steps.size(); i++) {
            RoadmapAiService.DraftStep step = steps.get(i);
            String priorText = step.dependsOnEntryId() != null
                    ? priorStepTextById.get(step.dependsOnEntryId()) : null;
            boolean needsBridge = priorText != null && step.riskScore() != null
                    && step.riskScore() >= BRIDGE_RISK_THRESHOLD;
            String bridgeText = needsBridge ? roadmapAi.bridgeStep(priorText, step.text()) : null;

            if (bridgeText != null) {
                result.add(new RoadmapAiService.DraftStep(bridgeText, "concept", "small", null,
                        step.dependsOnEntryId(), "Bridges into the next step."));
                int bridgeIndex = result.size() - 1;
                // The original step now depends on the bridge (same-batch) instead of reaching
                // across modules directly.
                result.add(new RoadmapAiService.DraftStep(step.text(), step.kind(), step.weight(),
                        bridgeIndex, null, step.rationale()));
            } else {
                // No bridge needed (or the call failed) — carry the step through unchanged, only
                // remapping its same-batch dependsOn index for any steps inserted before it.
                Integer remapped = step.dependsOn() != null ? oldToNew[step.dependsOn()] : null;
                result.add(new RoadmapAiService.DraftStep(step.text(), step.kind(), step.weight(),
                        remapped, step.dependsOnEntryId(), step.rationale(), step.riskScore()));
            }
            oldToNew[i] = result.size() - 1;
        }
        return result;
    }

    /**
     * Background retry for a module stuck with skeleton (titles-only) steps (Phase 19): re-runs
     * the full expansion now that a provider may have recovered, and if it succeeds, replaces the
     * skeleton steps with the fully-detailed ones in place. Returns {@code false} (try again
     * later) when the module isn't actually in skeleton state, still has any founder progress or
     * edits on it (never clobber real work), or the heavy chain still fails.
     */
    @Transactional
    public boolean retrySkeletonModule(Long moduleId) {
        Entry module = repository.findById(moduleId)
                .filter(e -> e.getType() == EntryType.ROADMAP)
                .orElse(null);
        if (module == null || module.getParentId() == null) {
            return false;
        }
        Long roadmapId = module.getParentId();
        Entry roadmap = repository.findById(roadmapId).orElse(null);
        if (roadmap == null) {
            return false;
        }
        List<Entry> oldSteps = repository.findByParentIdOrderByOrderIndexAsc(moduleId);
        boolean untouchedSkeleton = !oldSteps.isEmpty() && oldSteps.stream().allMatch(s ->
                s.getStatus() == EntryStatus.CAPTURED
                        && Boolean.TRUE.equals(s.getContent() != null ? s.getContent().get("skeletonOnly") : null));
        if (!untouchedSkeleton) {
            return false;
        }

        GenerateRoadmapResponse full = attemptFullExpand(roadmapId, roadmap, module);
        if (full == null) {
            return false;
        }

        repository.deleteAll(oldSteps);
        List<CreateRoadmapRequest.DraftStepInput> draftInputs = full.steps().stream()
                .map(s -> new CreateRoadmapRequest.DraftStepInput(s.text(), s.kind(), s.weight(),
                        s.dependsOn(), s.dependsOnEntryId(), s.rationale(),
                        s.resources().stream().map(r -> new CreateRoadmapRequest.ResourceInput(
                                null, r.title(), r.url(), r.format(), r.sourceType(),
                                r.estimatedTime(), r.aiGroundingSource())).toList(),
                        false))
                .toList();
        structureService.createDraftSteps(moduleId, draftInputs);
        repository.touchUpdatedAt(roadmapId, Instant.now());
        return true;
    }

    /** The roadmap's stored goal-scope read (Phase 18), formatted for a prompt; null if none. */
    private static String storedAssessmentContext(Entry roadmap) {
        Object raw = roadmap.getContent() != null ? roadmap.getContent().get("assessment") : null;
        if (!(raw instanceof Map<?, ?> map)) {
            return null;
        }
        int complexity = map.get("complexity") instanceof Number n ? n.intValue() : 3;
        Integer hours = map.get("estimatedTotalHours") instanceof Number n ? n.intValue() : null;
        String domain = map.get("domain") instanceof String s ? s : null;
        String priorLevel = map.get("priorLevel") instanceof String s ? s : null;
        String shape = map.get("shape") instanceof String s ? s : "nested";
        String archetype = map.get("archetype") instanceof String s ? s : null;
        return RoadmapAiService.assessmentContext(
                new RoadmapAiService.GoalAssessment(complexity, hours, domain, priorLevel, shape, archetype));
    }

    /** The roadmap's stored assessed domain (Phase 18/25) — picks a Phase 25 teaching persona. */
    private static String storedDomain(Entry roadmap) {
        Object raw = roadmap.getContent() != null ? roadmap.getContent().get("assessment") : null;
        return raw instanceof Map<?, ?> map && map.get("domain") instanceof String s ? s : null;
    }

    /** The roadmap's stored assessed complexity (1-5), or the mid-range default if none stored. */
    private static int storedComplexity(Entry roadmap) {
        Object raw = roadmap.getContent() != null ? roadmap.getContent().get("assessment") : null;
        return raw instanceof Map<?, ?> map && map.get("complexity") instanceof Number n ? n.intValue() : 3;
    }

    /** The roadmap's stored tier (RB-2.3), or null if never classified. */
    private static String storedTier(Entry roadmap) {
        Object raw = roadmap.getContent() != null ? roadmap.getContent().get("tier") : null;
        return raw instanceof String s ? s : null;
    }

    // Below this many steps, a self-critique pass isn't worth the call — too little content for
    // ordering/gap issues to mean anything (Phase 20).
    private static final int MIN_STEPS_FOR_CRITIQUE = 5;
    // Career-scale goals (Phase 18's complexity scale) get the deeper heavy-tier pass; everything
    // else gets the cheap fast-tier one, per the suggestions doc's Lightweight/Heavy split.
    private static final int HEAVY_CRITIQUE_COMPLEXITY = 4;

    /**
     * Self-critique a just-drafted step list (Phase 20), or skip and return no issues when it's
     * not worth the call: too few steps to say anything meaningful, or the AI is unavailable
     * (best-effort — a failed/skipped critique never blocks the draft it would have reviewed).
     * Escalates to the heavy tier either on assessed complexity (as before) or unconditionally
     * for {@code tier: "CAREER"} (RB-4.5) — a career-scale roadmap's validation matters enough to
     * always get the deeper pass, not just when complexity happens to also be high.
     */
    private List<RoadmapAiService.CritiqueIssue> critiqueIfWarranted(
            String goal, String scope, List<String> stepTexts, int complexity, String tier) {
        if (stepTexts.size() < MIN_STEPS_FOR_CRITIQUE || !roadmapAi.isAvailable()) {
            return List.of();
        }
        boolean heavy = complexity >= HEAVY_CRITIQUE_COMPLEXITY || "CAREER".equals(tier);
        return roadmapAi.critique(goal, scope, stepTexts, heavy);
    }

    /**
     * Redraft one module's title/scope (Phase 18) — propose half of "regenerate this module";
     * nothing changes until {@code updateModule} is called with the (possibly edited) result.
     */
    @Transactional(readOnly = true)
    public GenerateRoadmapResponse.ProposedModule regenerateModuleScope(Long roadmapId, Long moduleId) {
        Entry roadmap = queryService.getRoadmap(roadmapId);
        Entry module = queryService.requireModule(roadmapId, moduleId);
        if (!roadmapAi.isAvailable()) {
            throw new IllegalStateException("Drafting is unavailable right now — edit it yourself.");
        }
        RoadmapAiService.OutlineModule redraft = roadmapAi.regenerateModuleScope(
                stringOf(roadmap, "title"), stringOf(module, "title"), stringOf(module, "scope"),
                siblingModulesContext(roadmapId, moduleId));
        if (redraft == null) {
            throw new IllegalStateException("Couldn't redraft this module right now — edit it yourself.");
        }
        return GenerateRoadmapResponse.ProposedModule.from(redraft);
    }

    /**
     * Draft one new module to insert into this roadmap's outline (Phase 18) — propose half of
     * "insert a module here"; nothing changes until {@code insertModule} is called.
     */
    @Transactional(readOnly = true)
    public GenerateRoadmapResponse.ProposedModule proposeNewModule(Long roadmapId) {
        Entry roadmap = queryService.getRoadmap(roadmapId);
        if (!roadmapAi.isAvailable()) {
            throw new IllegalStateException("Drafting is unavailable right now — add it yourself.");
        }
        RoadmapAiService.OutlineModule proposed = roadmapAi.proposeModule(stringOf(roadmap, "title"),
                siblingModulesContext(roadmapId, null), storedAssessmentContext(roadmap));
        if (proposed == null) {
            throw new IllegalStateException("Couldn't draft a new module right now — add it yourself.");
        }
        return GenerateRoadmapResponse.ProposedModule.from(proposed);
    }

    /**
     * Draft one new module scoped to a specific subtopic (RB-3.8) — reached from a confirmed
     * Canonical Topic Match's SUBTOPIC decision. Same "propose" half as {@link #proposeNewModule},
     * biased toward the given focus instead of a free choice of gap; accept the same way, via
     * {@code insertModule} — direct reuse of Phase 18's existing insert-module mechanic, not a
     * new insertion path. {@code possibleDuplicate} is a soft, non-blocking flag (RB-3.8's
     * "lightweight duplication check") — never a reason to reject the proposal outright.
     */
    @Transactional(readOnly = true)
    public SubtopicModuleProposal proposeSubtopicModule(Long roadmapId, String focusGoal) {
        Entry roadmap = queryService.getRoadmap(roadmapId);
        if (!roadmapAi.isAvailable()) {
            throw new IllegalStateException("Drafting is unavailable right now — add it yourself.");
        }
        List<Entry> existingModules = repository.findByParentIdOrderByOrderIndexAsc(roadmapId).stream()
                .filter(e -> e.getType() == EntryType.ROADMAP).toList();
        RoadmapAiService.OutlineModule proposed = roadmapAi.proposeModule(stringOf(roadmap, "title"),
                siblingModulesContext(roadmapId, null), storedAssessmentContext(roadmap), focusGoal);
        if (proposed == null) {
            throw new IllegalStateException("Couldn't draft a new module right now — add it yourself.");
        }
        boolean possibleDuplicate = existingModules.stream().anyMatch(m -> similarTitle(
                stringOf(m, "title"), proposed.title()));
        return new SubtopicModuleProposal(proposed.title(), proposed.scope(), possibleDuplicate);
    }

    /** A loose, no-false-confidence text similarity check — flag only, never block (RB-3.8). */
    static boolean similarTitle(String a, String b) {
        if (a == null || b == null) {
            return false;
        }
        String na = a.trim().toLowerCase(java.util.Locale.ROOT);
        String nb = b.trim().toLowerCase(java.util.Locale.ROOT);
        return na.equals(nb) || na.contains(nb) || nb.contains(na);
    }

    /** One proposed module scoped to a subtopic (RB-3.8), plus a soft duplication flag. */
    public record SubtopicModuleProposal(String title, String scope, boolean possibleDuplicate) {
    }

    /** This roadmap's other modules, formatted as plain context; null if there are none. */
    private String siblingModulesContext(Long roadmapId, Long excludeModuleId) {
        StringBuilder sb = new StringBuilder();
        for (Entry sibling : repository.findByParentIdOrderByOrderIndexAsc(roadmapId)) {
            if (sibling.getType() != EntryType.ROADMAP
                    || (excludeModuleId != null && excludeModuleId.equals(sibling.getId()))) {
                continue;
            }
            String t = stringOf(sibling, "title");
            if (t == null || t.isBlank()) {
                continue;
            }
            sb.append("- ").append(t);
            String s = stringOf(sibling, "scope");
            if (s != null && !s.isBlank()) {
                sb.append(": ").append(s);
            }
            sb.append('\n');
        }
        return sb.length() == 0 ? null : sb.toString();
    }

    /**
     * Redraft every not-yet-expanded module given real progress so far (Phase 18) — "replan
     * remaining modules." Propose half; nothing changes until {@code applyReplan} is called
     * with the (possibly edited) result. Each item carries its module's real id so accepting it
     * doesn't need any index translation.
     */
    @Transactional(readOnly = true)
    public List<ReplanModuleItem> replanRemainingModules(Long roadmapId) {
        Entry roadmap = queryService.getRoadmap(roadmapId);
        if (!roadmapAi.isAvailable()) {
            throw new IllegalStateException("Drafting is unavailable right now — edit modules yourself.");
        }

        List<Entry> expanded = new ArrayList<>();
        List<Entry> remaining = new ArrayList<>();
        for (Entry m : repository.findByParentIdOrderByOrderIndexAsc(roadmapId)) {
            if (m.getType() != EntryType.ROADMAP) {
                continue;
            }
            boolean hasSteps = !repository.findByParentIdOrderByOrderIndexAsc(m.getId()).isEmpty();
            (hasSteps ? expanded : remaining).add(m);
        }
        if (remaining.isEmpty()) {
            throw new IllegalArgumentException("Every module is already expanded — nothing to replan.");
        }

        List<RoadmapAiService.OutlineModule> redrafted = roadmapAi.replanModules(
                stringOf(roadmap, "title"), expandedModulesContext(expanded),
                remainingModulesContext(remaining), storedAssessmentContext(roadmap), remaining.size());
        if (redrafted == null) {
            throw new IllegalStateException(
                    "Couldn't replan the remaining modules right now — edit them yourself.");
        }

        List<ReplanModuleItem> result = new ArrayList<>();
        for (int i = 0; i < remaining.size(); i++) {
            RoadmapAiService.OutlineModule m = redrafted.get(i);
            result.add(new ReplanModuleItem(
                    remaining.get(i).getId(), m.title(), m.scope()));
        }
        return result;
    }

    /** Already-expanded modules with their real done/total counts, for the replan prompt. */
    private String expandedModulesContext(List<Entry> modules) {
        StringBuilder sb = new StringBuilder();
        for (Entry m : modules) {
            List<Entry> steps = repository.findByParentIdOrderByOrderIndexAsc(m.getId());
            long done = steps.stream().filter(s -> s.getStatus() == EntryStatus.DONE).count();
            sb.append("- ").append(stringOf(m, "title"));
            String scope = stringOf(m, "scope");
            if (scope != null && !scope.isBlank()) {
                sb.append(": ").append(scope);
            }
            sb.append(" (").append(done).append('/').append(steps.size()).append(" steps done)\n");
        }
        return sb.length() == 0 ? null : sb.toString();
    }

    /** Not-yet-expanded modules in order, for the replan prompt. */
    private String remainingModulesContext(List<Entry> modules) {
        StringBuilder sb = new StringBuilder();
        for (Entry m : modules) {
            sb.append("- ").append(stringOf(m, "title"));
            String scope = stringOf(m, "scope");
            if (scope != null && !scope.isBlank()) {
                sb.append(": ").append(scope);
            }
            sb.append('\n');
        }
        return sb.toString();
    }

    /** Fold answered clarifying questions into a plain block for the proposal prompt. */
    private static String formatClarifications(List<GenerateRoadmapRequest.Clarification> items) {
        StringBuilder sb = new StringBuilder();
        for (GenerateRoadmapRequest.Clarification c : items) {
            String answer = c.answer() != null ? c.answer().trim() : "";
            if (answer.isEmpty()) {
                continue;
            }
            if (c.question() != null && !c.question().isBlank()) {
                sb.append("Q: ").append(c.question().trim()).append('\n');
            }
            sb.append("A: ").append(answer).append('\n');
        }
        return sb.toString();
    }

    /**
     * The 2–4 "what this covers" bullets for a step (Phase 7.5 deep view). Generated on first
     * open and cached in the step's content, so re-opening is instant and free. Throws
     * {@link IllegalStateException} if the AI can't summarize it right now.
     */
    @Transactional
    @SuppressWarnings("unchecked")
    public List<String> stepCovers(Long stepId) {
        Entry step = repository.findById(stepId)
                .filter(e -> e.getType() == EntryType.ROADMAP_STEP)
                .orElseThrow(() -> new java.util.NoSuchElementException("No step " + stepId));

        Map<String, Object> content = step.getContent() != null
                ? new HashMap<>(step.getContent()) : new HashMap<>();
        Object cached = content.get("covers");
        if (cached instanceof List<?> list && !list.isEmpty()) {
            return list.stream().map(String::valueOf).toList();
        }

        String roadmapTitle = step.getParentId() == null ? null
                : repository.findById(step.getParentId()).map(r -> stringOf(r, "title")).orElse(null);
        List<String> covers = roadmapAi.stepCovers(roadmapTitle, stringOf(step, "text"));
        if (covers == null) {
            throw new IllegalStateException("Couldn't summarize this step right now.");
        }
        content.put("covers", covers);
        step.setContent(content);
        repository.save(step);
        return covers;
    }

    private static String stringOf(Entry entry, String key) {
        Object value = entry != null && entry.getContent() != null ? entry.getContent().get(key) : null;
        return value instanceof String s ? s : null;
    }
}
