package com.compass.app.roadmap;

import com.compass.app.ai.AiVoiceService;
import com.compass.app.ai.EmbeddingService;
import com.compass.app.ai.ResourceAiService;
import com.compass.app.ai.ReviewAiService;
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
import com.compass.app.roadmap.dto.ApplyReTierProposalRequest;
import com.compass.app.roadmap.dto.CreateRoadmapRequest;
import com.compass.app.roadmap.dto.GenerateRoadmapRequest;
import com.compass.app.roadmap.dto.GenerateRoadmapResponse;
import com.compass.app.roadmap.dto.ModuleExpansionResult;
import com.compass.app.roadmap.dto.ReTierRequest;
import com.compass.app.roadmap.dto.ReTierResponse;
import com.compass.app.roadmap.dto.ReplanModuleItem;
import com.compass.app.topic.CanonicalTopic;
import com.compass.app.topic.CanonicalTopicRepository;
import com.compass.app.topic.TopicMatcherService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;

/**
 * Roadmaps are just entries: a {@code roadmap} row plus ordered {@code roadmap_step}
 * children pointing at it via parent_id (see CLAUDE.md Section 4). No separate tables.
 */
@Service
public class RoadmapService {

    private final EntryRepository repository;
    private final RoadmapAiService roadmapAi;
    private final ProfileService profileService;
    private final SearchGroundingService searchGrounding;
    private final ResourceService resourceService;
    private final EntryService entryService;
    private final AiVoiceService aiVoice;
    private final EventService events;
    private final TopicMatcherService topicMatcher;
    private final CanonicalTopicRepository canonicalTopics;
    private final EmbeddingService embeddings;
    private final ReviewAiService reviewAi;
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

    public RoadmapService(EntryRepository repository, RoadmapAiService roadmapAi,
                          ProfileService profileService, SearchGroundingService searchGrounding,
                          ResourceService resourceService, EntryService entryService,
                          AiVoiceService aiVoice, EventService events,
                          TopicMatcherService topicMatcher, CanonicalTopicRepository canonicalTopics,
                          EmbeddingService embeddings, ReviewAiService reviewAi,
                          ExecutorService expansionExecutor,
                          @org.springframework.beans.factory.annotation.Value(
                                  "${compass.search.max-context-snippets:5}") int maxGroundingSnippets) {
        this.repository = repository;
        this.roadmapAi = roadmapAi;
        this.profileService = profileService;
        this.searchGrounding = searchGrounding;
        this.resourceService = resourceService;
        this.entryService = entryService;
        this.topicMatcher = topicMatcher;
        this.canonicalTopics = canonicalTopics;
        this.embeddings = embeddings;
        this.reviewAi = reviewAi;
        this.aiVoice = aiVoice;
        this.events = events;
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
     * currently running via {@code onStage} (Phase 18) — used by {@link GenerationJobService} so
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
    private static RoadmapAiService.GoalAssessment reconcileShapeWithTier(
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
     * {@link #expandModule}, concurrently rather than sequentially, capped at
     * {@value #MAX_CONCURRENT_EXPANSIONS} at once so a batch of many modules doesn't trip rate
     * limits across the whole provider chain simultaneously. One module failing doesn't affect
     * the others — each result records its own success or error.
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
     * same link never appears on two steps. Nothing is persisted — accept via
     * {@link #addStepsToModule}. Throws {@link IllegalStateException} when drafting fails.
     */
    @Transactional(readOnly = true)
    public GenerateRoadmapResponse expandModule(Long roadmapId, Long moduleId) {
        Entry roadmap = getRoadmap(roadmapId);
        Entry module = requireModule(roadmapId, moduleId);
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
        createDraftSteps(moduleId, draftInputs);
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

    /** Accept a module's expanded steps (Phase 13) — same shape and validation as roadmap steps. */
    @Transactional
    public void addStepsToModule(Long roadmapId, Long moduleId,
                                  List<CreateRoadmapRequest.DraftStepInput> draftSteps) {
        requireModule(roadmapId, moduleId);
        createDraftSteps(moduleId, draftSteps);
        repository.touchUpdatedAt(roadmapId, Instant.now());
    }

    private Entry requireModule(Long roadmapId, Long moduleId) {
        return repository.findById(moduleId)
                .filter(e -> e.getType() == EntryType.ROADMAP && roadmapId.equals(e.getParentId()))
                .orElseThrow(() -> new java.util.NoSuchElementException(
                        "No module " + moduleId + " on roadmap " + roadmapId));
    }

    /**
     * Ids of this roadmap's not-yet-expanded modules — any direct child module with no steps of
     * its own yet. Used by {@link ModulePrefetchService} (via the controller) to know which
     * modules to draft in the background whenever one appears or changes.
     */
    @Transactional(readOnly = true)
    public List<Long> unexpandedModuleIds(Long roadmapId) {
        List<Long> ids = new ArrayList<>();
        for (Entry m : repository.findByParentIdOrderByOrderIndexAsc(roadmapId)) {
            if (m.getType() == EntryType.ROADMAP
                    && repository.findByParentIdOrderByOrderIndexAsc(m.getId()).isEmpty()) {
                ids.add(m.getId());
            }
        }
        return ids;
    }

    /**
     * Redraft one module's title/scope (Phase 18) — propose half of "regenerate this module";
     * nothing changes until {@link #updateModule} is called with the (possibly edited) result.
     */
    @Transactional(readOnly = true)
    public GenerateRoadmapResponse.ProposedModule regenerateModuleScope(Long roadmapId, Long moduleId) {
        Entry roadmap = getRoadmap(roadmapId);
        Entry module = requireModule(roadmapId, moduleId);
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

    /** Apply an edited module title/scope (Phase 18) — the accept half of {@link #regenerateModuleScope}. */
    @Transactional
    public void updateModule(Long roadmapId, Long moduleId, String title, String scope) {
        Entry module = requireModule(roadmapId, moduleId);
        String trimmedTitle = title != null ? title.trim() : "";
        if (trimmedTitle.isEmpty()) {
            throw new IllegalArgumentException("A module needs a title.");
        }
        Map<String, Object> content = module.getContent() != null
                ? new HashMap<>(module.getContent()) : new HashMap<>();
        content.put("title", trimmedTitle);
        if (scope != null && !scope.isBlank()) {
            content.put("scope", scope.trim());
        } else {
            content.remove("scope");
        }
        module.setContent(content);
        repository.save(module);
        repository.touchUpdatedAt(roadmapId, Instant.now());
    }

    /**
     * Draft one new module to insert into this roadmap's outline (Phase 18) — propose half of
     * "insert a module here"; nothing changes until {@link #insertModule} is called.
     */
    @Transactional(readOnly = true)
    public GenerateRoadmapResponse.ProposedModule proposeNewModule(Long roadmapId) {
        Entry roadmap = getRoadmap(roadmapId);
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
     * {@link #insertModule} — direct reuse of Phase 18's existing insert-module mechanic, not a
     * new insertion path. {@code possibleDuplicate} is a soft, non-blocking flag (RB-3.8's
     * "lightweight duplication check") — never a reason to reject the proposal outright.
     */
    @Transactional(readOnly = true)
    public SubtopicModuleProposal proposeSubtopicModule(Long roadmapId, String focusGoal) {
        Entry roadmap = getRoadmap(roadmapId);
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
    private static boolean similarTitle(String a, String b) {
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

    /** Insert a new, empty module at {@code position} (Phase 18) — accept half of {@link #proposeNewModule}. */
    @Transactional
    public Entry insertModule(Long roadmapId, String title, String scope, Integer position) {
        getRoadmap(roadmapId);
        String trimmedTitle = title != null ? title.trim() : "";
        if (trimmedTitle.isEmpty()) {
            throw new IllegalArgumentException("A module needs a title.");
        }

        List<Entry> modules = repository.findByParentIdOrderByOrderIndexAsc(roadmapId).stream()
                .filter(e -> e.getType() == EntryType.ROADMAP)
                .collect(java.util.stream.Collectors.toCollection(ArrayList::new));
        int insertAt = position == null ? modules.size() : Math.max(0, Math.min(position, modules.size()));

        Entry module = new Entry();
        module.setType(EntryType.ROADMAP);
        module.setStatus(EntryStatus.IN_MOTION);
        module.setParentId(roadmapId);
        Map<String, Object> content = new HashMap<>();
        content.put("title", trimmedTitle);
        if (scope != null && !scope.isBlank()) {
            content.put("scope", scope.trim());
        }
        module.setContent(content);

        modules.add(insertAt, module);
        reindexAndSave(modules);
        repository.touchUpdatedAt(roadmapId, Instant.now());
        return module;
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
     * remaining modules." Propose half; nothing changes until {@link #applyReplan} is called
     * with the (possibly edited) result. Each item carries its module's real id so accepting it
     * doesn't need any index translation.
     */
    @Transactional(readOnly = true)
    public List<ReplanModuleItem> replanRemainingModules(Long roadmapId) {
        Entry roadmap = getRoadmap(roadmapId);
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

    /** Apply an accepted replan (Phase 18) — the accept half of {@link #replanRemainingModules}. */
    @Transactional
    public void applyReplan(Long roadmapId, List<ReplanModuleItem> modules) {
        for (ReplanModuleItem m : modules) {
            updateModule(roadmapId, m.moduleId(), m.title(), m.scope());
        }
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

    // Total tree depth allowed below the roadmap root (Phase 20) — module(1)/step(2)/substep(3),
    // no deeper. Counted from the root, not by role, so a flat roadmap's step(1)/substep(2)
    // naturally gets one more level of break-down room than a nested one, which is fine: the cap
    // is about total nesting, not about labeling every level "module" or "step".
    private static final int MAX_STEP_DEPTH = 3;

    /** How many parents up to the root roadmap (root itself is depth 0). */
    private int depthOf(Entry entry) {
        if (entry.getParentId() == null) {
            return 0;
        }
        // One recursive query instead of a findById per level (V3-3.1).
        return repository.findAncestors(entry.getId()).size();
    }

    /**
     * True when {@code stepId} is already as deep as the nesting cap allows, so breaking it down
     * further would be rejected (Phase 20) — checked early (before spending an AI call on a draft
     * that could never be applied) as well as enforced again in {@link #splitStep} itself.
     */
    @Transactional(readOnly = true)
    public boolean isAtMaxStepDepth(Long stepId) {
        return repository.findById(stepId)
                .map(this::depthOf)
                .map(depth -> depth >= MAX_STEP_DEPTH)
                .orElse(false);
    }

    /**
     * The assessed domain (Phase 18) of the roadmap a node belongs to, walking up from any node
     * (step, module, or the root itself) to the root entry that actually carries the stored
     * assessment — modules and steps never carry their own. Used to pick a Phase 25 teaching
     * persona for reformulate/resurfacing-triggered breakdowns, which only have a step or module
     * id in hand, not the root. Null if the node doesn't exist or nothing was ever assessed.
     */
    @Transactional(readOnly = true)
    public String domainOf(Long nodeId) {
        Entry node = repository.findById(nodeId).orElse(null);
        if (node == null) {
            return null;
        }
        // findAncestors returns nearest-first, so the last element is the root (V3-3.1) — one
        // query rather than one per level.
        List<Entry> ancestors = repository.findAncestors(nodeId);
        Entry root = ancestors.isEmpty() ? node : ancestors.get(ancestors.size() - 1);
        return root.getContent() != null && root.getContent().get("assessment") instanceof Map<?, ?> assessment
                && assessment.get("domain") instanceof String s ? s : null;
    }

    /**
     * Every leaf {@code roadmap_step} anywhere in this roadmap's tree — modules and steps-with-
     * substeps are containers, not leaves, and are skipped. Callers that need "the real units of
     * work" (e.g. review's stalled-step scan) should use this instead of {@link #stepsOf}, which
     * only returns direct children and, for a nested roadmap, that's module entries, not steps.
     */
    @Transactional(readOnly = true)
    public List<Entry> leafStepsOf(Long roadmapId) {
        // The whole subtree in one query, walked in memory (V3-3.1). The old version recursed
        // with a query per node, so a career roadmap cost 60+ round trips to build this list.
        List<Entry> descendants = repository.findDescendants(roadmapId);
        Set<Long> parents = descendants.stream()
                .map(Entry::getParentId)
                .filter(java.util.Objects::nonNull)
                .collect(java.util.stream.Collectors.toSet());
        return descendants.stream()
                .filter(e -> e.getType() == EntryType.ROADMAP_STEP)
                .filter(e -> !parents.contains(e.getId()))
                .toList();
    }

    // (proposal() maps the AI draft steps to the structured response DTO.)

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

    @Transactional
    public Entry create(CreateRoadmapRequest req) {
        String title = req.title() != null ? req.title().trim() : "";
        if (title.isEmpty()) {
            throw new IllegalArgumentException("A roadmap needs a title.");
        }

        Map<String, Object> content = new HashMap<>();
        content.put("title", title);
        if (req.notes() != null && !req.notes().isBlank()) {
            content.put("notes", req.notes().trim());
        }
        if (req.assessment() != null) {
            content.put("assessment", assessmentMap(req.assessment()));
        }
        if (req.tier() != null && !req.tier().isBlank()) {
            content.put("tier", req.tier());
        }

        Entry roadmap = new Entry();
        roadmap.setType(EntryType.ROADMAP);
        roadmap.setStatus(EntryStatus.IN_MOTION);
        roadmap.setContent(content);
        roadmap = repository.save(roadmap);

        if (req.draftSteps() != null && !req.draftSteps().isEmpty()) {
            createDraftSteps(roadmap.getId(), req.draftSteps());
        } else {
            int order = 0;
            for (String stepText : req.steps() != null ? req.steps() : List.<String>of()) {
                if (stepText == null || stepText.isBlank()) {
                    continue;
                }
                Entry step = new Entry();
                step.setType(EntryType.ROADMAP_STEP);
                step.setStatus(EntryStatus.CAPTURED);
                step.setParentId(roadmap.getId());
                step.setOrderIndex(order++);
                Map<String, Object> stepContent = new HashMap<>();
                stepContent.put("text", stepText.trim());
                step.setContent(stepContent);
                repository.save(step);
            }
        }

        if (req.modules() != null && !req.modules().isEmpty()) {
            createModules(roadmap.getId(), req.modules());
        }

        saveCanonicalTopic(roadmap, title);
        return roadmap;
    }

    /**
     * Save this newly-created roadmap as a Canonical Topic (RB-3.9) so a future goal can match
     * against it. Best-effort: an embedding failure just skips this, never blocks roadmap
     * creation — same pattern as every other AI call in this codebase. A title collision (same
     * slug already used) also just skips rather than erroring, since re-creating "the same"
     * roadmap under a slightly different title is a normal, harmless thing to do.
     */
    private void saveCanonicalTopic(Entry roadmap, String title) {
        String topicId = slugify(title);
        if (topicId.isEmpty() || canonicalTopics.findByTopicId(topicId).isPresent()) {
            return;
        }
        List<Double> embedding = embeddings.embed(title);
        if (embedding == null) {
            return;
        }
        CanonicalTopic topic = new CanonicalTopic();
        topic.setTopicId(topicId);
        topic.setCanonicalName(title);
        topic.setEmbedding(embedding);
        topic.setCreatedFrom(title);
        topic.setRoadmapEntryId(roadmap.getId());
        canonicalTopics.save(topic);
    }

    private static String slugify(String text) {
        if (text == null) {
            return "";
        }
        String slug = text.trim().toLowerCase(java.util.Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("(^-|-$)", "");
        return slug.length() <= 128 ? slug : slug.substring(0, 128);
    }

    /** The stored form of an accepted assessment (Phase 18) for the roadmap's content JSONB. */
    private static Map<String, Object> assessmentMap(CreateRoadmapRequest.AssessmentInput a) {
        Map<String, Object> map = new java.util.LinkedHashMap<>();
        map.put("complexity", a.complexity());
        map.put("estimatedTotalHours", a.estimatedTotalHours());
        map.put("domain", a.domain());
        map.put("priorLevel", a.priorLevel());
        map.put("shape", a.shape());
        map.put("archetype", a.archetype());
        return map;
    }

    /**
     * Create empty module entries from an accepted outline (Phase 13) — each a child roadmap
     * under the root, with no steps yet. Steps are added later via {@link #expandModule} +
     * {@link #addStepsToModule}, so depth only grows where the user asks for it.
     */
    private void createModules(Long roadmapId, List<CreateRoadmapRequest.ModuleInput> modules) {
        int order = 0;
        for (CreateRoadmapRequest.ModuleInput m : modules) {
            if (m == null || m.title() == null || m.title().isBlank()) {
                continue;
            }
            Entry module = new Entry();
            module.setType(EntryType.ROADMAP);
            module.setStatus(EntryStatus.IN_MOTION);
            module.setParentId(roadmapId);
            module.setOrderIndex(order++);
            Map<String, Object> moduleContent = new HashMap<>();
            moduleContent.put("title", m.title().trim());
            if (m.scope() != null && !m.scope().isBlank()) {
                moduleContent.put("scope", m.scope().trim());
            }
            module.setContent(moduleContent);
            repository.save(module);
        }
    }

    /**
     * Create structured steps accepted from an AI proposal (Phase 7): store kind/weight in
     * content, then resolve each step's {@code dependsOn} index into the real prerequisite
     * step's id. Two passes because a dependency can only be linked once both steps have ids.
     */
    private void createDraftSteps(Long roadmapId, List<CreateRoadmapRequest.DraftStepInput> draftSteps) {
        List<Entry> created = new ArrayList<>();
        int order = 0;
        for (CreateRoadmapRequest.DraftStepInput draft : draftSteps) {
            if (draft == null || draft.text() == null || draft.text().isBlank()) {
                created.add(null); // keep index alignment for dependsOn mapping
                continue;
            }
            Entry step = new Entry();
            step.setType(EntryType.ROADMAP_STEP);
            step.setStatus(EntryStatus.CAPTURED);
            step.setParentId(roadmapId);
            step.setOrderIndex(order++);
            Map<String, Object> stepContent = new HashMap<>();
            stepContent.put("text", draft.text().trim());
            if (draft.kind() != null && !draft.kind().isBlank()) {
                stepContent.put("kind", draft.kind());
            }
            if (draft.weight() != null && !draft.weight().isBlank()) {
                stepContent.put("weight", draft.weight());
            }
            if (draft.rationale() != null && !draft.rationale().isBlank()) {
                stepContent.put("rationale", draft.rationale().trim());
            }
            List<Map<String, Object>> resources = buildResources(draft.resources());
            if (!resources.isEmpty()) {
                stepContent.put("resources", resources);
            }
            if (draft.skeletonOnly()) {
                stepContent.put("skeletonOnly", true);
            }
            step.setContent(stepContent);
            created.add(repository.save(step));
        }

        for (int i = 0; i < draftSteps.size(); i++) {
            Entry step = created.get(i);
            CreateRoadmapRequest.DraftStepInput draft = draftSteps.get(i);
            if (step == null || draft == null) {
                continue;
            }
            // A cross-module id (Phase 18) is already a real, existing step — resolve it
            // directly, no index translation needed.
            if (draft.dependsOnEntryId() != null) {
                step.setDependsOn(draft.dependsOnEntryId());
                repository.save(step);
                continue;
            }
            Integer dep = draft.dependsOn();
            if (dep == null || dep < 0 || dep >= created.size() || dep == i) {
                continue;
            }
            Entry prerequisite = created.get(dep);
            if (prerequisite != null) {
                step.setDependsOn(prerequisite.getId());
                repository.save(step);
            }
        }
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

    /** A roadmap's steps in order. */
    @Transactional(readOnly = true)
    public List<Entry> stepsOf(Long roadmapId) {
        return repository.findByParentIdOrderByOrderIndexAsc(roadmapId);
    }

    /**
     * The one-time CAREER completion reflection (RB-4.7): checked after a step is marked done,
     * cheap enough to call every time — a no-op unless this roadmap is {@code tier: "CAREER"},
     * every leaf step is DONE, and it hasn't already reflected once. {@code null} means nothing
     * new to show (not complete yet, not CAREER, already reflected, or the AI call failed).
     */
    @Transactional
    public String checkCareerCompletion(Long roadmapId) {
        Entry roadmap = getRoadmap(roadmapId);
        if (!"CAREER".equals(storedTier(roadmap))
                || roadmap.getContent() != null && roadmap.getContent().get("completionReflection") != null) {
            return null;
        }
        List<Entry> leaves = leafStepsOf(roadmapId);
        if (leaves.isEmpty() || leaves.stream().anyMatch(s -> s.getStatus() != EntryStatus.DONE)) {
            return null;
        }
        String reflection = reviewAi.careerCompletionReflection(stringOf(roadmap, "title"));
        if (reflection == null) {
            return null;
        }
        Map<String, Object> content = new HashMap<>(
                roadmap.getContent() != null ? roadmap.getContent() : Map.of());
        content.put("completionReflection", reflection);
        content.put("completionReflectedAt", Instant.now().toString());
        roadmap.setContent(content);
        repository.save(roadmap);
        return reflection;
    }

    /**
     * Two-way completion sync (RB-4.8) and module completion rollup (RB-4.12), one shared
     * mechanism: both a step-with-substeps and a module are just a container whose children can
     * roll up into it. Call after marking {@code stepId} done — a no-op otherwise (only
     * completion propagates; un-marking done never cascades, so undoing one substep doesn't
     * silently un-complete a whole module).
     * <ul>
     *   <li>Parent → children: every substep of a just-completed container marks done too.</li>
     *   <li>Children → parent: if this step's own parent is a real container (a step with
     *       substeps, or a module — never the top-level roadmap itself, which only completes via
     *       {@link #checkCareerCompletion}'s explicit CAREER-only path) and every sibling is now
     *       done, the parent marks done too.</li>
     * </ul>
     */
    @Transactional
    public void syncStepCompletion(Long stepId) {
        Entry step = repository.findById(stepId)
                .filter(s -> s.getType() == EntryType.ROADMAP_STEP)
                .orElseThrow(() -> new java.util.NoSuchElementException("No step " + stepId));
        if (step.getStatus() != EntryStatus.DONE) {
            return;
        }

        for (Entry child : repository.findByParentIdOrderByOrderIndexAsc(stepId)) {
            if (child.getStatus() != EntryStatus.DONE) {
                child.setStatus(EntryStatus.DONE);
                repository.save(child);
            }
        }

        if (step.getParentId() == null) {
            return;
        }
        Entry parent = repository.findById(step.getParentId()).orElse(null);
        boolean parentIsRealContainer = parent != null
                && (parent.getType() == EntryType.ROADMAP_STEP
                        || (parent.getType() == EntryType.ROADMAP && parent.getParentId() != null));
        if (!parentIsRealContainer || parent.getStatus() == EntryStatus.DONE) {
            return;
        }
        List<Entry> siblings = repository.findByParentIdOrderByOrderIndexAsc(parent.getId());
        boolean allDone = !siblings.isEmpty() && siblings.stream().allMatch(s -> s.getStatus() == EntryStatus.DONE);
        if (allDone) {
            parent.setStatus(EntryStatus.DONE);
            repository.save(parent);
        }
    }

    /**
     * Active top-level roadmaps (archived excluded), newest first. Child roadmaps (modules,
     * Phase 13) have a parent and are shown inside their root, never as their own list entry.
     */
    @Transactional(readOnly = true)
    public List<Entry> listRoadmaps() {
        return repository.findByTypeOrderByCreatedAtDesc(EntryType.ROADMAP).stream()
                .filter(r -> r.getParentId() == null)
                .filter(r -> r.getStatus() != EntryStatus.ARCHIVED)
                .toList();
    }

    /** Archived top-level roadmaps only, newest first (the Archive view). */
    @Transactional(readOnly = true)
    public List<Entry> listArchivedRoadmaps() {
        return repository.findByTypeOrderByCreatedAtDesc(EntryType.ROADMAP).stream()
                .filter(r -> r.getParentId() == null)
                .filter(r -> r.getStatus() == EntryStatus.ARCHIVED)
                .toList();
    }

    /**
     * Archive or unarchive a roadmap (Phase 12): archiving drops it out of the main list into
     * the Archive view without losing it; unarchiving restores it. Nothing is deleted.
     */
    @Transactional
    public Entry setArchived(Long roadmapId, boolean archived) {
        Entry roadmap = getRoadmap(roadmapId);
        roadmap.setStatus(archived ? EntryStatus.ARCHIVED : EntryStatus.IN_MOTION);
        return repository.save(roadmap);
    }

    /** Delete a whole roadmap and every step under it (Phase 12). Not reversible. */
    @Transactional
    public void deleteRoadmap(Long roadmapId) {
        getRoadmap(roadmapId); // 404 if it isn't a roadmap
        repository.deleteAll(stepsOf(roadmapId));
        repository.deleteById(roadmapId);
    }

    /**
     * The re-tier escape hatch (RB-2.5) — a founder-triggered correction when the classifier got
     * a goal's scale wrong. Never automatic; always the founder's own action, logged as such
     * ({@code source: founder}). Which path runs is decided by the roadmap's REAL current
     * structure (does it have modules with their own steps, or is it a flat list?), not by the
     * stored {@code tier} field — a roadmap created before RB-2.3, or one whose classification
     * failed, still re-tiers correctly this way.
     */
    @Transactional
    public ReTierResponse reTier(Long roadmapId, ReTierRequest request) {
        Entry roadmap = getRoadmap(roadmapId);
        Tier newTier = parseTier(request.tier());
        List<Entry> topChildren = repository.findByParentIdOrderByOrderIndexAsc(roadmapId);
        // A module is type ROADMAP (see createModules); a flat roadmap's direct children are
        // leaf ROADMAP_STEPs instead. Checking the TYPE, not whether a module already has
        // children, matters because an accepted-but-not-yet-expanded module is still a real
        // module (Type ROADMAP, zero children) — not a flat step to be swept into a grouping call.
        boolean currentlyNested = topChildren.stream().anyMatch(c -> c.getType() == EntryType.ROADMAP);

        if (newTier == Tier.TASK) {
            return reTierToTask(roadmap);
        }
        if (newTier == Tier.MINI) {
            return currentlyNested ? flattenToMini(roadmap, topChildren) : relabelOnly(roadmap, newTier);
        }
        // newTier is TOPIC or CAREER — both a nested (modules-then-steps) shape.
        if (!currentlyNested && !topChildren.isEmpty()) {
            // Only MINI → TOPIC is a defined single-step grouping transition (RB-2.5) — a flat
            // roadmap going straight to CAREER goes through TOPIC first, same as any other
            // adjacent-tier move, rather than inventing an ungrouped MINI → CAREER path.
            if (newTier == Tier.CAREER) {
                throw new IllegalArgumentException(
                        "Re-tier to TOPIC first, then TOPIC → CAREER — there's no direct MINI → CAREER path.");
            }
            return proposeRegroup(roadmap, topChildren);
        }
        if (newTier == Tier.CAREER && currentlyNested) {
            // Already nested; offer the optional arc-order proposal rather than forcing one —
            // the founder can also just confirm an empty/no-op reorder if the current order is
            // already fine (see applyReTierProposal's arc_order handling).
            return proposeArcOrder(roadmap, topChildren);
        }
        // Already nested, target TOPIC (e.g. from CAREER), or no steps at all yet either way —
        // nothing structural to change; phases aren't a real stored entity (RB-4.3), so CAREER →
        // TOPIC has nothing to strip beyond the label itself.
        return relabelOnly(roadmap, newTier);
    }

    /** Confirm a previously-returned re-tier proposal (RB-2.5), applying the founder's groups/order. */
    @Transactional
    public ReTierResponse applyReTierProposal(Long roadmapId, ApplyReTierProposalRequest request) {
        Entry roadmap = getRoadmap(roadmapId);
        if ("regroup".equals(request.kind())) {
            return applyRegroup(roadmap, request.groups());
        }
        if ("arc_order".equals(request.kind())) {
            return applyArcOrder(roadmap, request.groups());
        }
        throw new IllegalArgumentException("Unknown re-tier proposal kind: " + request.kind());
    }

    private static Tier parseTier(String raw) {
        try {
            return Tier.valueOf(raw == null ? "" : raw.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Unknown tier: " + raw);
        }
    }

    /** Update just the stored {@code tier} label — no structural change (RB-2.5). */
    private ReTierResponse relabelOnly(Entry roadmap, Tier newTier) {
        String from = stringOf(roadmap, "tier");
        setTierContent(roadmap, newTier);
        events.founderAction("re_tier", "roadmap re-tiered from " + (from == null ? "unset" : from)
                + " to " + newTier + " — label only, no structural change",
                Map.of("roadmapId", roadmap.getId()));
        return ReTierResponse.applied(roadmap.getId());
    }

    private void setTierContent(Entry roadmap, Tier tier) {
        Map<String, Object> content = new HashMap<>(
                roadmap.getContent() != null ? roadmap.getContent() : Map.of());
        content.put("tier", tier.name());
        roadmap.setContent(content);
        repository.save(roadmap);
    }

    /** Convert a whole roadmap to a task (RB-2.5): archive it, create a task with its title. */
    private ReTierResponse reTierToTask(Entry roadmap) {
        String title = stringOf(roadmap, "title");
        Entry task = entryService.create(new CreateEntryRequest(EntryType.TASK, title, null, null, null, null));
        String ack = aiVoice.acknowledge(task);
        roadmap.setStatus(EntryStatus.ARCHIVED);
        repository.save(roadmap);
        events.founderAction("re_tier", "roadmap re-tiered to TASK — archived, converted to a task entry",
                Map.of("roadmapId", roadmap.getId(), "taskEntryId", task.getId()));
        return ReTierResponse.taskConversion(task.getId(), title, ack);
    }

    /**
     * Flatten a nested roadmap to MINI (RB-2.5): every leaf step (already-existing rows, kept
     * as-is — completion status survives automatically since nothing is recreated) is reparented
     * directly under the roadmap in order; the now-empty module rows are removed. The original
     * module titles are logged, not silently lost, in case the founder wants to reconstruct them.
     */
    private ReTierResponse flattenToMini(Entry roadmap, List<Entry> topChildren) {
        List<Entry> leaves = leafStepsOf(roadmap.getId());
        Set<Long> leafIds = leaves.stream().map(Entry::getId).collect(java.util.stream.Collectors.toSet());
        List<Entry> emptiedModules = topChildren.stream()
                .filter(c -> !leafIds.contains(c.getId()))
                .toList();

        int order = 0;
        for (Entry leaf : leaves) {
            leaf.setParentId(roadmap.getId());
            leaf.setOrderIndex(order++);
            repository.save(leaf);
        }
        // Archived, not deleted (RB-2.5): parentId stays put (so it's still found and cleaned up
        // if the whole roadmap is later deleted) but ARCHIVED status hides it from the rendered
        // tree (see RoadmapNodeResponse.of) — the row survives with its title/scope intact,
        // reversible if a re-tier turns out to be the wrong call, unlike a hard delete.
        for (Entry module : emptiedModules) {
            module.setStatus(EntryStatus.ARCHIVED);
            repository.save(module);
        }
        setTierContent(roadmap, Tier.MINI);
        List<String> moduleTitles = emptiedModules.stream()
                .map(c -> stringOf(c, "title")).filter(t -> t != null && !t.isBlank()).toList();
        List<Long> moduleIds = emptiedModules.stream().map(Entry::getId).toList();
        events.founderAction("re_tier", "roadmap re-tiered to MINI — archived "
                + emptiedModules.size() + " module(s) (titles: " + String.join(", ", moduleTitles) + ")",
                Map.of("roadmapId", roadmap.getId(), "archivedModuleIds", moduleIds));
        return ReTierResponse.applied(roadmap.getId());
    }

    /** AI-assisted grouping proposal for MINI → TOPIC (RB-2.5) — nothing applied yet. */
    private ReTierResponse proposeRegroup(Entry roadmap, List<Entry> flatSteps) {
        String title = stringOf(roadmap, "title");
        List<RoadmapAiService.StepForGrouping> steps = flatSteps.stream()
                .map(s -> new RoadmapAiService.StepForGrouping(s.getId(), stringOf(s, "text")))
                .toList();
        List<RoadmapAiService.RegroupedModule> groups = roadmapAi.regroupSteps(title, steps);
        if (groups == null) {
            throw new IllegalStateException(
                    "Couldn't propose a grouping right now — try again shortly.");
        }
        List<ReTierResponse.Group> proposed = groups.stream()
                .map(g -> new ReTierResponse.Group(g.title(), g.scope(), g.stepIds(), null))
                .toList();
        return ReTierResponse.proposal("regroup", proposed);
    }

    /** AI-assisted arc-order proposal for TOPIC → CAREER (RB-2.5) — nothing applied yet. */
    private ReTierResponse proposeArcOrder(Entry roadmap, List<Entry> modules) {
        String title = stringOf(roadmap, "title");
        List<RoadmapAiService.ModuleForArc> forArc = modules.stream()
                .map(m -> new RoadmapAiService.ModuleForArc(m.getId(), stringOf(m, "title"), stringOf(m, "scope")))
                .toList();
        List<RoadmapAiService.ArcPosition> order = roadmapAi.proposeCareerArc(title, forArc);
        if (order == null) {
            throw new IllegalStateException(
                    "Couldn't propose an arc order right now — try again shortly.");
        }
        List<ReTierResponse.Group> proposed = order.stream()
                .map(p -> new ReTierResponse.Group(null, null, List.of(p.moduleId()), p.phaseLabel()))
                .toList();
        return ReTierResponse.proposal("arc_order", proposed);
    }

    /**
     * Apply a confirmed regroup proposal (RB-2.5, MINI → TOPIC/CAREER): create one new module per
     * group, reparenting its referenced (already-existing) steps under it — their completion
     * status survives untouched since they're the same rows, just moved. Any step the founder's
     * confirmed groups leave out still lands somewhere (a catch-all "Ungrouped" module) rather
     * than being silently dropped; a step that was already DONE and got left out gets its own
     * brief log entry too, since that's the case actually worth the founder's attention.
     */
    private ReTierResponse applyRegroup(Entry roadmap, List<ReTierResponse.Group> groups) {
        List<Entry> allSteps = repository.findByParentIdOrderByOrderIndexAsc(roadmap.getId());
        Map<Long, Entry> stepsById = allSteps.stream()
                .collect(java.util.stream.Collectors.toMap(Entry::getId, s -> s));
        Set<Long> grouped = new HashSet<>();

        int moduleOrder = 0;
        for (ReTierResponse.Group g : groups) {
            List<Entry> members = new ArrayList<>();
            for (Long stepId : g.entryIds() == null ? List.<Long>of() : g.entryIds()) {
                Entry step = stepsById.get(stepId);
                if (step != null) {
                    members.add(step);
                    grouped.add(stepId);
                }
            }
            if (members.isEmpty()) {
                continue;
            }
            createModuleWithSteps(roadmap.getId(), moduleOrder++, g.title(), g.scope(), members);
        }

        List<Entry> leftOver = allSteps.stream().filter(s -> !grouped.contains(s.getId())).toList();
        List<String> droppedDoneTitles = leftOver.stream()
                .filter(s -> s.getStatus() == EntryStatus.DONE)
                .map(s -> stringOf(s, "text"))
                .toList();
        if (!leftOver.isEmpty()) {
            createModuleWithSteps(roadmap.getId(), moduleOrder, "Ungrouped",
                    "Steps the proposed grouping didn't place — sorted here instead of dropped.", leftOver);
        }

        // Regroup is only ever proposed for MINI → TOPIC (reTier() rejects a direct MINI →
        // CAREER grouping) — TOPIC is always the right target tier to store here.
        setTierContent(roadmap, Tier.TOPIC);
        String msg = "roadmap re-tiered to TOPIC — regrouped " + allSteps.size() + " step(s) into "
                + (moduleOrder + (leftOver.isEmpty() ? 0 : 1)) + " module(s)"
                + (droppedDoneTitles.isEmpty() ? "" : "; completed step(s) left out of the proposed"
                        + " groups, sorted into Ungrouped instead: " + String.join(", ", droppedDoneTitles));
        events.founderAction("re_tier", msg, Map.of("roadmapId", roadmap.getId()));
        return ReTierResponse.applied(roadmap.getId());
    }

    /** Create one module under a roadmap with the given already-existing steps reparented into it. */
    private void createModuleWithSteps(Long roadmapId, int orderIndex, String title, String scope,
                                       List<Entry> steps) {
        Entry module = new Entry();
        module.setType(EntryType.ROADMAP); // a module is type ROADMAP, same as createModules()
        module.setStatus(EntryStatus.IN_MOTION);
        module.setParentId(roadmapId);
        module.setOrderIndex(orderIndex);
        Map<String, Object> moduleContent = new HashMap<>();
        moduleContent.put("title", title == null || title.isBlank() ? "Module" : title);
        if (scope != null && !scope.isBlank()) {
            moduleContent.put("scope", scope);
        }
        module.setContent(moduleContent);
        module = repository.save(module);
        int stepOrder = 0;
        for (Entry step : steps) {
            step.setParentId(module.getId());
            step.setOrderIndex(stepOrder++);
            repository.save(step);
        }
    }

    /**
     * Apply a confirmed arc-order proposal (RB-2.5, TOPIC → CAREER): reorder the existing modules
     * — nothing is renamed, created, or dropped, {@code phaseLabel} was informational only.
     */
    private ReTierResponse applyArcOrder(Entry roadmap, List<ReTierResponse.Group> order) {
        List<Entry> modules = repository.findByParentIdOrderByOrderIndexAsc(roadmap.getId());
        Map<Long, Entry> byId = modules.stream()
                .collect(java.util.stream.Collectors.toMap(Entry::getId, m -> m));
        int i = 0;
        for (ReTierResponse.Group g : order) {
            Long moduleId = g.entryIds() == null || g.entryIds().isEmpty() ? null : g.entryIds().get(0);
            Entry module = moduleId == null ? null : byId.get(moduleId);
            if (module == null) {
                continue;
            }
            module.setOrderIndex(i++);
            repository.save(module);
        }
        setTierContent(roadmap, Tier.CAREER);
        events.founderAction("re_tier", "roadmap re-tiered to CAREER — reordered "
                + i + " module(s) into the confirmed arc", Map.of("roadmapId", roadmap.getId()));
        return ReTierResponse.applied(roadmap.getId());
    }

    @Transactional(readOnly = true)
    public Entry getRoadmap(Long id) {
        Entry entry = repository.findById(id)
                .filter(e -> e.getType() == EntryType.ROADMAP)
                .orElseThrow(() -> new java.util.NoSuchElementException(
                        "No roadmap with id " + id));
        return entry;
    }

    /**
     * Reorder a roadmap's steps to match {@code orderedStepIds} exactly (every current
     * step, no more, no less) — a partial or mismatched list is rejected rather than
     * silently dropping steps.
     */
    @Transactional
    public void reorderSteps(Long roadmapId, List<Long> orderedStepIds) {
        List<Entry> steps = stepsOf(roadmapId);
        Map<Long, Entry> byId = new HashMap<>();
        for (Entry step : steps) {
            byId.put(step.getId(), step);
        }
        if (orderedStepIds == null || orderedStepIds.size() != steps.size()
                || !byId.keySet().equals(new HashSet<>(orderedStepIds))) {
            throw new IllegalArgumentException(
                    "Reorder must include exactly this roadmap's current steps.");
        }

        List<Entry> reordered = new ArrayList<>();
        for (Long stepId : orderedStepIds) {
            reordered.add(byId.get(stepId));
        }
        reindexAndSave(reordered);
        repository.touchUpdatedAt(roadmapId, Instant.now());
    }

    /**
     * Insert a new step at {@code position} (0-based), shifting later steps down.
     * A null or out-of-range position appends to the end.
     */
    @Transactional
    public Entry insertStep(Long roadmapId, String text, Integer position) {
        getRoadmap(roadmapId);
        String trimmed = text != null ? text.trim() : "";
        if (trimmed.isEmpty()) {
            throw new IllegalArgumentException("A step needs some text.");
        }

        List<Entry> steps = stepsOf(roadmapId);
        int insertAt = position == null
                ? steps.size()
                : Math.max(0, Math.min(position, steps.size()));

        Entry step = new Entry();
        step.setType(EntryType.ROADMAP_STEP);
        step.setStatus(EntryStatus.CAPTURED);
        step.setParentId(roadmapId);
        Map<String, Object> content = new HashMap<>();
        content.put("text", trimmed);
        step.setContent(content);

        steps.add(insertAt, step);
        reindexAndSave(steps);
        repository.touchUpdatedAt(roadmapId, Instant.now());
        return step;
    }

    /**
     * Break one step into smaller SUBSTEPS underneath it (Phase 4, reshaped by Phase 13's tree
     * model) — the "break this step down" restructuring. The original step stays (now a
     * container, like a module); the substeps are what's actually worked through. Anywhere in
     * the tree, not just directly under the root. Substeps carry kind/weight/rationale/resources
     * (Phase 20), same as any other accepted draft — reuses {@link #createDraftSteps} rather than
     * a separate plain-text path.
     */
    @Transactional
    public void splitStep(Long roadmapId, Long stepId, List<CreateRoadmapRequest.DraftStepInput> draftSteps) {
        List<CreateRoadmapRequest.DraftStepInput> clean = draftSteps == null ? List.of()
                : draftSteps.stream()
                        .filter(d -> d != null && d.text() != null && !d.text().isBlank())
                        .toList();
        if (clean.isEmpty()) {
            throw new IllegalArgumentException("Give at least one step to replace it with.");
        }

        Entry original = repository.findById(stepId)
                .filter(s -> s.getType() == EntryType.ROADMAP_STEP)
                .orElseThrow(() -> new java.util.NoSuchElementException(
                        "No step " + stepId + " on roadmap " + roadmapId));
        if (depthOf(original) >= MAX_STEP_DEPTH) {
            throw new IllegalStateException("This is already broken down as far as it goes.");
        }

        createDraftSteps(original.getId(), clean);
        inheritResources(original);
        repository.touchUpdatedAt(roadmapId, Instant.now());
    }

    /**
     * RB-4.8: substeps inherit the parent's own resources — a substep with no resources of its
     * own (the common case; resource discovery for a break-down proposal isn't run today) gets
     * the same material its parent already had, rather than starting from nothing. A substep the
     * founder (or resource discovery) already gave real resources to is left alone.
     */
    @SuppressWarnings("unchecked")
    private void inheritResources(Entry original) {
        Object parentResources = original.getContent() != null ? original.getContent().get("resources") : null;
        if (!(parentResources instanceof List<?> list) || list.isEmpty()) {
            return;
        }
        for (Entry substep : repository.findByParentIdOrderByOrderIndexAsc(original.getId())) {
            Object existing = substep.getContent() != null ? substep.getContent().get("resources") : null;
            if (existing instanceof List<?> existingList && !existingList.isEmpty()) {
                continue;
            }
            Map<String, Object> content = new HashMap<>(
                    substep.getContent() != null ? substep.getContent() : Map.of());
            content.put("resources", new ArrayList<>((List<Map<String, Object>>) list));
            substep.setContent(content);
            repository.save(substep);
        }
    }

    /**
     * Insert a prerequisite step immediately before {@code stepId}, as its sibling under
     * whichever parent it actually lives under (root roadmap, a module, or another step's
     * substeps), and record it as that step's real prerequisite (depends_on) — the "something's
     * missing first" restructuring (Phase 4).
     */
    @Transactional
    public Entry addPrerequisite(Long roadmapId, Long stepId, String prerequisiteText) {
        String text = prerequisiteText != null ? prerequisiteText.trim() : "";
        if (text.isEmpty()) {
            throw new IllegalArgumentException("A prerequisite needs some text.");
        }

        Entry target = repository.findById(stepId)
                .filter(s -> s.getType() == EntryType.ROADMAP_STEP)
                .orElseThrow(() -> new java.util.NoSuchElementException(
                        "No step " + stepId + " on roadmap " + roadmapId));
        List<Entry> siblings = repository.findByParentIdOrderByOrderIndexAsc(target.getParentId());
        int index = indexOfStep(siblings, stepId, roadmapId);

        Entry prerequisite = newStep(target.getParentId(), text);
        siblings.add(index, prerequisite);
        reindexAndSave(siblings); // assigns the new step its id
        target.setDependsOn(prerequisite.getId());
        repository.save(target);
        repository.touchUpdatedAt(roadmapId, Instant.now());
        return prerequisite;
    }

    /**
     * "Promote back up" — flatten (Phase 20): delete a container step's substeps, reverting it
     * to a plain leaf. Only allowed when none of the substeps have any real progress on them
     * (still {@code captured}, no notes, no session history) — decided in favor of a hard
     * precondition over a destructive-action confirm dialog, consistent with how
     * {@link #retrySkeletonModule} already guards against clobbering real work: cheap to check,
     * and never silently loses something the founder actually did.
     */
    @Transactional
    public void flattenStep(Long roadmapId, Long stepId) {
        Entry container = repository.findById(stepId)
                .filter(s -> s.getType() == EntryType.ROADMAP_STEP)
                .orElseThrow(() -> new java.util.NoSuchElementException(
                        "No step " + stepId + " on roadmap " + roadmapId));
        List<Entry> substeps = repository.findByParentIdOrderByOrderIndexAsc(stepId);
        if (substeps.isEmpty()) {
            throw new IllegalArgumentException("This step has no substeps to flatten.");
        }
        if (substeps.stream().anyMatch(RoadmapService::hasRealProgress)) {
            throw new IllegalStateException(
                    "These substeps have real progress on them — nothing to flatten safely.");
        }
        repository.deleteAll(substeps);
        repository.touchUpdatedAt(roadmapId, Instant.now());
    }

    /** Any sign of real engagement — self-marked progress, notes, or a tracked session. */
    @SuppressWarnings("unchecked")
    private static boolean hasRealProgress(Entry step) {
        if (step.getStatus() != EntryStatus.CAPTURED) {
            return true;
        }
        Map<String, Object> content = step.getContent();
        if (content == null) {
            return false;
        }
        Object notes = content.get("notes");
        if (notes instanceof String s && !s.isBlank()) {
            return true;
        }
        Object history = content.get("sessionHistory");
        return history instanceof List<?> l && !l.isEmpty();
    }

    /**
     * "Promote back up" — graduate (Phase 20): reparent one substep to become a sibling of its
     * current parent (under the same module/root) instead of nested beneath it, right after that
     * parent in order. Preserves its own substeps (if any — the nesting cap means there normally
     * aren't, but this doesn't assume that). Reindexes both the old and new sibling lists.
     */
    @Transactional
    public void graduateStep(Long roadmapId, Long stepId) {
        Entry step = repository.findById(stepId)
                .filter(s -> s.getType() == EntryType.ROADMAP_STEP)
                .orElseThrow(() -> new java.util.NoSuchElementException(
                        "No step " + stepId + " on roadmap " + roadmapId));
        Entry oldParent = step.getParentId() == null ? null
                : repository.findById(step.getParentId()).orElse(null);
        if (oldParent == null || oldParent.getType() != EntryType.ROADMAP_STEP) {
            throw new IllegalArgumentException("This step isn't nested under another step.");
        }
        Long newParentId = oldParent.getParentId();

        List<Entry> oldSiblings = repository.findByParentIdOrderByOrderIndexAsc(oldParent.getId());
        oldSiblings.removeIf(s -> s.getId().equals(stepId));
        reindexAndSave(oldSiblings);

        List<Entry> newSiblings = repository.findByParentIdOrderByOrderIndexAsc(newParentId);
        int afterOldParent = indexOfStep(newSiblings, oldParent.getId(), roadmapId) + 1;
        step.setParentId(newParentId);
        newSiblings.add(Math.min(afterOldParent, newSiblings.size()), step);
        reindexAndSave(newSiblings);
        repository.touchUpdatedAt(roadmapId, Instant.now());
    }

    /**
     * Turn accepted resource inputs into stored resource maps, dropping anything malformed. The
     * shape itself is defined once in {@link ResourceService#storedResource} — the backfill path
     * writes the same maps, and two hand-written copies of the same field names would eventually
     * disagree.
     */
    private static List<Map<String, Object>> buildResources(List<CreateRoadmapRequest.ResourceInput> inputs) {
        List<Map<String, Object>> resources = new ArrayList<>();
        if (inputs == null) {
            return resources;
        }
        for (CreateRoadmapRequest.ResourceInput r : inputs) {
            if (r == null) {
                continue;
            }
            Map<String, Object> map = ResourceService.storedResource(r.id(), r.title(), r.url(),
                    r.format(), r.sourceType(), r.estimatedTime(), r.aiGroundingSource());
            if (map != null) {
                resources.add(map);
            }
        }
        return resources;
    }

    private Entry newStep(Long roadmapId, String text) {
        Entry step = new Entry();
        step.setType(EntryType.ROADMAP_STEP);
        step.setStatus(EntryStatus.CAPTURED);
        step.setParentId(roadmapId);
        Map<String, Object> content = new HashMap<>();
        content.put("text", text);
        step.setContent(content);
        return step;
    }

    private static int indexOfStep(List<Entry> steps, Long stepId, Long roadmapId) {
        for (int i = 0; i < steps.size(); i++) {
            if (steps.get(i).getId().equals(stepId)) {
                return i;
            }
        }
        throw new java.util.NoSuchElementException("No step " + stepId + " on roadmap " + roadmapId);
    }

    /**
     * Delete a step anywhere in the roadmap's tree and close the order_index gap under its
     * parent (Phase 13). Any substeps under it go too, via the parent FK's ON DELETE CASCADE.
     */
    @Transactional
    public void deleteStep(Long roadmapId, Long stepId) {
        Entry toDelete = repository.findById(stepId)
                .filter(s -> s.getType() == EntryType.ROADMAP_STEP)
                .orElseThrow(() -> new java.util.NoSuchElementException(
                        "No step " + stepId + " on roadmap " + roadmapId));
        Long parentId = toDelete.getParentId();

        repository.delete(toDelete);
        List<Entry> siblings = repository.findByParentIdOrderByOrderIndexAsc(parentId);
        reindexAndSave(siblings);
        repository.touchUpdatedAt(roadmapId, Instant.now());
    }

    /** Reassigns order_index 0..n-1 to match list order, then persists all of them. */
    private void reindexAndSave(List<Entry> orderedSteps) {
        for (int i = 0; i < orderedSteps.size(); i++) {
            orderedSteps.get(i).setOrderIndex(i);
        }
        repository.saveAll(orderedSteps);
    }
}
