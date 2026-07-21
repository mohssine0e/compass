package com.compass.app.roadmap;

import com.compass.app.ai.RoadmapAiService;
import com.compass.app.ai.SearchGroundingService;
import com.compass.app.entry.Entry;
import com.compass.app.entry.EntryRepository;
import com.compass.app.entry.EntryStatus;
import com.compass.app.entry.EntryType;
import com.compass.app.profile.ProfileContext;
import com.compass.app.profile.ProfileService;
import com.compass.app.roadmap.dto.CreateRoadmapRequest;
import com.compass.app.roadmap.dto.GenerateRoadmapRequest;
import com.compass.app.roadmap.dto.GenerateRoadmapResponse;
import com.compass.app.roadmap.dto.ModuleExpansionResult;
import com.compass.app.roadmap.dto.ReplanModuleItem;
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
import java.util.concurrent.Executors;

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
    // Search results arrive relevance-ordered; only the top few are worth spending prompt tokens
    // on (Phase 19) — smaller prompts are faster and cheaper on every provider, especially the
    // slowest tier. Resource discovery separately reads the full uncapped result set.
    private final int maxGroundingSnippets;
    // Bounds concurrency for expandModulesBatch (Phase 19) so a batch of many modules doesn't
    // trip rate limits across the whole provider chain at once.
    private static final int MAX_CONCURRENT_EXPANSIONS = 4;
    private final ExecutorService expansionExecutor = Executors.newFixedThreadPool(MAX_CONCURRENT_EXPANSIONS);

    public RoadmapService(EntryRepository repository, RoadmapAiService roadmapAi,
                          ProfileService profileService, SearchGroundingService searchGrounding,
                          @org.springframework.beans.factory.annotation.Value(
                                  "${compass.search.max-context-snippets:5}") int maxGroundingSnippets) {
        this.repository = repository;
        this.roadmapAi = roadmapAi;
        this.profileService = profileService;
        this.searchGrounding = searchGrounding;
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

        if (req.clarifications() == null) {
            onStage.accept(GenerationStage.CLARIFYING);
            List<String> questions = roadmapAi.clarifyingQuestions(goal, profileContext);
            if (questions == null) {
                throw new IllegalStateException(
                        "Drafting is unavailable right now — write the steps yourself.");
            }
            if (questions.isEmpty()) {
                // Nothing genuinely worth asking — draft straight away rather than showing an
                // empty question form; the outline prompt states its assumptions plainly instead.
                return draft(goal, "", profileContext, onStage);
            }
            return GenerateRoadmapResponse.needsClarification(questions);
        }

        String firstRoundQa = formatClarifications(req.clarifications());
        if (!req.skipFollowUp()) {
            onStage.accept(GenerationStage.CLARIFYING);
            List<String> followUps = roadmapAi.followUpQuestions(goal, firstRoundQa, profileContext);
            // followUps == null means the follow-up check itself failed (unavailable/error) —
            // treat that the same as "nothing to add" rather than blocking drafting on it.
            if (followUps != null && !followUps.isEmpty()) {
                return GenerateRoadmapResponse.needsClarification(followUps);
            }
        }

        return draft(goal, firstRoundQa, profileContext, onStage);
    }

    /**
     * The actual drafting call (Phase 18), shared by the zero-questions and answered-questions
     * paths. Grounds once, assesses the goal's scope once, then gates on {@code shape}: a small
     * goal drafts straight to a flat step list; a bigger one drafts the module outline as before.
     * A failed assessment falls back to today's un-assessed behavior (nested, mid-range) rather
     * than blocking generation on it.
     */
    private GenerateRoadmapResponse draft(String goal, String clarificationsText, String profileContext,
                                          java.util.function.Consumer<GenerationStage> onStage) {
        // Ground once, in real sources when a search key is configured; null (and no sources)
        // when it isn't, and generation proceeds ungrounded. Shared by assessment and drafting.
        SearchGroundingService.Grounding grounding = searchGrounding.ground(goal);
        String groundingContext = grounding == null ? null : grounding.contextTop(maxGroundingSnippets);
        List<String> sources = grounding == null ? List.of() : grounding.sources();

        onStage.accept(GenerationStage.ASSESSING);
        RoadmapAiService.GoalAssessment assessment = roadmapAi.assessGoal(
                goal, clarificationsText, profileContext, groundingContext);
        if (assessment == null) {
            assessment = new RoadmapAiService.GoalAssessment(3, null, null, null, "nested");
        }
        String assessmentContext = RoadmapAiService.assessmentContext(assessment);

        if ("flat".equals(assessment.shape())) {
            onStage.accept(GenerationStage.DRAFTING);
            RoadmapAiService.FlatProposal flat = roadmapAi.proposeFlat(
                    goal, clarificationsText, profileContext, groundingContext, assessmentContext);
            if (flat == null) {
                throw new IllegalStateException(
                        "Drafting is unavailable right now — write the steps yourself.");
            }
            onStage.accept(GenerationStage.FINDING_RESOURCES);
            List<String> stepTexts = flat.steps().stream().map(RoadmapAiService.DraftStep::text).toList();
            List<List<RoadmapAiService.Resource>> resources = roadmapAi.suggestResources(
                    goal, stepTexts, grounding == null ? null : grounding.results(),
                    avoidedFormats(), Set.of());
            return GenerateRoadmapResponse.proposal(flat.title(), flat.interpretation(), flat.steps(),
                    resources, flat.skipped(), sources, assessment, Map.of());
        }

        onStage.accept(GenerationStage.DRAFTING);
        RoadmapAiService.RoadmapOutline outline = roadmapAi.moduleOutline(
                goal, clarificationsText, profileContext, groundingContext, assessmentContext);
        if (outline == null) {
            throw new IllegalStateException(
                    "Drafting is unavailable right now — write the steps yourself.");
        }
        return GenerateRoadmapResponse.outline(outline.title(), outline.interpretation(),
                outline.modules(), outline.skipped(), sources, assessment);
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
        List<List<RoadmapAiService.Resource>> noResources = skeletonSteps.stream()
                .map(s -> List.<RoadmapAiService.Resource>of()).toList();
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
        SearchGroundingService.Grounding grounding = searchGrounding.ground(groundingQuery);
        String groundingContext = grounding == null ? null : grounding.contextTop(maxGroundingSnippets);
        List<String> sources = grounding == null ? List.of() : grounding.sources();

        List<RoadmapAiService.DraftStep> steps = roadmapAi.expandModule(roadmapTitle, moduleTitle,
                moduleScope, profileContext, groundingContext, assessmentContext, priorSteps);
        if (steps == null) {
            return null;
        }
        List<String> stepTexts = steps.stream().map(RoadmapAiService.DraftStep::text).toList();
        List<List<RoadmapAiService.Resource>> resources = roadmapAi.suggestResources(
                moduleTitle, stepTexts, grounding == null ? null : grounding.results(),
                avoidedFormats(), usedResourceUrls(roadmapId));
        return GenerateRoadmapResponse.proposal(moduleTitle, null, steps, resources, List.of(),
                sources, null, priorStepTextById);
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
        return RoadmapAiService.assessmentContext(
                new RoadmapAiService.GoalAssessment(complexity, hours, domain, priorLevel, shape));
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

    /** Every resource url already attached anywhere in this roadmap's tree (Phase 13 dedup). */
    @Transactional(readOnly = true)
    public Set<String> usedResourceUrls(Long roadmapId) {
        Set<String> urls = new HashSet<>();
        collectResourceUrls(roadmapId, urls);
        return urls;
    }

    @SuppressWarnings("unchecked")
    private void collectResourceUrls(Long parentId, Set<String> urls) {
        for (Entry child : repository.findByParentIdOrderByOrderIndexAsc(parentId)) {
            if (child.getType() == EntryType.ROADMAP_STEP && child.getContent() != null
                    && child.getContent().get("resources") instanceof List<?> list) {
                for (Object item : list) {
                    if (item instanceof Map<?, ?> map && map.get("url") instanceof String url
                            && !url.isBlank()) {
                        urls.add(url);
                    }
                }
            }
            collectResourceUrls(child.getId(), urls);
        }
    }

    /**
     * Every leaf {@code roadmap_step} anywhere in this roadmap's tree — modules and steps-with-
     * substeps are containers, not leaves, and are skipped. Callers that need "the real units of
     * work" (e.g. review's stalled-step scan) should use this instead of {@link #stepsOf}, which
     * only returns direct children and, for a nested roadmap, that's module entries, not steps.
     */
    @Transactional(readOnly = true)
    public List<Entry> leafStepsOf(Long roadmapId) {
        List<Entry> leaves = new ArrayList<>();
        collectLeafSteps(roadmapId, leaves);
        return leaves;
    }

    private void collectLeafSteps(Long parentId, List<Entry> out) {
        for (Entry child : repository.findByParentIdOrderByOrderIndexAsc(parentId)) {
            List<Entry> grandchildren = repository.findByParentIdOrderByOrderIndexAsc(child.getId());
            if (grandchildren.isEmpty()) {
                if (child.getType() == EntryType.ROADMAP_STEP) {
                    out.add(child);
                }
            } else {
                collectLeafSteps(child.getId(), out);
            }
        }
    }

    /** The formats the founder marked to avoid (confirmed profile only), or empty. */
    @SuppressWarnings("unchecked")
    private List<String> avoidedFormats() {
        return profileService.confirmedProfile()
                .map(p -> p.getFormatPreferences())
                .map(prefs -> prefs.get("avoid"))
                .filter(a -> a instanceof List)
                .map(a -> (List<String>) a)
                .orElseGet(List::of);
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

        return roadmap;
    }

    /** The stored form of an accepted assessment (Phase 18) for the roadmap's content JSONB. */
    private static Map<String, Object> assessmentMap(CreateRoadmapRequest.AssessmentInput a) {
        Map<String, Object> map = new java.util.LinkedHashMap<>();
        map.put("complexity", a.complexity());
        map.put("estimatedTotalHours", a.estimatedTotalHours());
        map.put("domain", a.domain());
        map.put("priorLevel", a.priorLevel());
        map.put("shape", a.shape());
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
     * the tree, not just directly under the root.
     */
    @Transactional
    public void splitStep(Long roadmapId, Long stepId, List<String> replacements) {
        List<String> clean = replacements == null ? List.<String>of()
                : replacements.stream()
                        .map(s -> s == null ? "" : s.trim())
                        .filter(s -> !s.isEmpty())
                        .toList();
        if (clean.isEmpty()) {
            throw new IllegalArgumentException("Give at least one step to replace it with.");
        }

        Entry original = repository.findById(stepId)
                .filter(s -> s.getType() == EntryType.ROADMAP_STEP)
                .orElseThrow(() -> new java.util.NoSuchElementException(
                        "No step " + stepId + " on roadmap " + roadmapId));

        List<Entry> substeps = clean.stream().map(text -> newStep(original.getId(), text)).toList();
        reindexAndSave(substeps);
        repository.touchUpdatedAt(roadmapId, Instant.now());
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
     * Turn accepted resource inputs into stored resource maps: keep only ones with a real url
     * and title, give each a stable id (generated if the client didn't send one), and start
     * user_rating null. Drops anything malformed.
     */
    private static List<Map<String, Object>> buildResources(List<CreateRoadmapRequest.ResourceInput> inputs) {
        List<Map<String, Object>> resources = new ArrayList<>();
        if (inputs == null) {
            return resources;
        }
        for (CreateRoadmapRequest.ResourceInput r : inputs) {
            if (r == null || r.title() == null || r.title().isBlank()
                    || r.url() == null || r.url().isBlank()) {
                continue;
            }
            Map<String, Object> map = new java.util.LinkedHashMap<>();
            map.put("id", r.id() != null && !r.id().isBlank() ? r.id() : java.util.UUID.randomUUID().toString());
            map.put("title", r.title().trim());
            map.put("url", r.url().trim());
            if (r.format() != null && !r.format().isBlank()) {
                map.put("format", r.format());
            }
            if (r.sourceType() != null && !r.sourceType().isBlank()) {
                map.put("sourceType", r.sourceType());
            }
            if (r.estimatedTime() != null && !r.estimatedTime().isBlank()) {
                map.put("estimatedTime", r.estimatedTime().trim());
            }
            if (r.aiGroundingSource() != null && !r.aiGroundingSource().isBlank()) {
                map.put("aiGroundingSource", r.aiGroundingSource().trim());
            }
            map.put("userRating", null);
            resources.add(map);
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
