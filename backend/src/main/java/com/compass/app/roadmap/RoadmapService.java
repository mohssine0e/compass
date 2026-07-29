package com.compass.app.roadmap;

import com.compass.app.entry.Entry;
import com.compass.app.roadmap.dto.ApplyReTierProposalRequest;
import com.compass.app.roadmap.dto.CreateRoadmapRequest;
import com.compass.app.roadmap.dto.GenerateRoadmapRequest;
import com.compass.app.roadmap.dto.GenerateRoadmapResponse;
import com.compass.app.roadmap.dto.ModuleExpansionResult;
import com.compass.app.roadmap.dto.ReTierRequest;
import com.compass.app.roadmap.dto.ReTierResponse;
import com.compass.app.roadmap.dto.ReplanModuleItem;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Roadmaps are just entries: a {@code roadmap} row plus ordered {@code roadmap_step}
 * children pointing at it via parent_id (see CLAUDE.md Section 4). No separate tables.
 *
 * <p>A thin facade (V3-4.1's split of the former god-file — see {@code TASKS_v3.md}) over four
 * collaborators, kept as the single injected type the rest of the app depends on so none of its
 * 8 external callers (controller, {@code GenerationWorker}, {@code ModulePrefetchService},
 * {@code SkeletonRetryService}, and the reformulate/resurfacing/review/verification services)
 * needed to change: {@link RoadmapQueryService} (read-only lookups), {@link RoadmapRetierService}
 * (the RB-2.5 re-tier escape hatch), {@link RoadmapStructureService} (creating/editing the tree),
 * and {@link RoadmapGenerationService} (the AI-facing drafting/expansion pipeline).
 */
@Service
public class RoadmapService {

    private final RoadmapQueryService queryService;
    private final RoadmapRetierService retierService;
    private final RoadmapStructureService structureService;
    private final RoadmapGenerationService generationService;

    public RoadmapService(RoadmapQueryService queryService, RoadmapRetierService retierService,
                          RoadmapStructureService structureService,
                          RoadmapGenerationService generationService) {
        this.queryService = queryService;
        this.retierService = retierService;
        this.structureService = structureService;
        this.generationService = generationService;
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
        return generationService.generate(req);
    }

    /**
     * As {@link #generate(GenerateRoadmapRequest)}, but reports which {@link GenerationStage} is
     * currently running via {@code onStage} (Phase 18) — used by {@link GenerationWorker} so
     * a slow AI call (the free-tier tertiary provider can take up to a minute) shows live progress
     * instead of a frozen wait. Called with a no-op consumer by the synchronous overload above.
     */
    GenerateRoadmapResponse generate(GenerateRoadmapRequest req, java.util.function.Consumer<GenerationStage> onStage) {
        return generationService.generate(req, onStage);
    }

    /**
     * Expand more than one module at once (Phase 19), only on the founder's explicit request —
     * the existing "expand on demand" single-module flow (JIT, quota-conscious per Phase 13)
     * stays the silent default. Each module's expansion runs independently, concurrently rather
     * than sequentially. One module failing doesn't affect the others.
     */
    public List<ModuleExpansionResult> expandModulesBatch(Long roadmapId, List<Long> moduleIds) {
        return generationService.expandModulesBatch(roadmapId, moduleIds);
    }

    /**
     * Expand one module of a roadmap into its own proposed steps (Phase 13), grounded on that
     * module's own title/scope (not the whole goal) so search stays relevant and resources stay
     * scoped. Nothing is persisted — accept via {@link #addStepsToModule}. Throws {@link
     * IllegalStateException} when drafting fails.
     */
    @Transactional(readOnly = true)
    public GenerateRoadmapResponse expandModule(Long roadmapId, Long moduleId) {
        return generationService.expandModule(roadmapId, moduleId);
    }

    /**
     * Background retry for a module stuck with skeleton (titles-only) steps (Phase 19): re-runs
     * the full expansion now that a provider may have recovered, and if it succeeds, replaces the
     * skeleton steps with the fully-detailed ones in place.
     */
    @Transactional
    public boolean retrySkeletonModule(Long moduleId) {
        return generationService.retrySkeletonModule(moduleId);
    }

    /** Accept a module's expanded steps (Phase 13) — same shape and validation as roadmap steps. */
    @Transactional
    public void addStepsToModule(Long roadmapId, Long moduleId,
                                  List<CreateRoadmapRequest.DraftStepInput> draftSteps) {
        structureService.addStepsToModule(roadmapId, moduleId, draftSteps);
    }

    /**
     * Ids of this roadmap's not-yet-expanded modules — any direct child module with no steps of
     * its own yet. Used by {@link ModulePrefetchService} (via the controller) to know which
     * modules to draft in the background whenever one appears or changes.
     */
    @Transactional(readOnly = true)
    public List<Long> unexpandedModuleIds(Long roadmapId) {
        return queryService.unexpandedModuleIds(roadmapId);
    }

    /**
     * Redraft one module's title/scope (Phase 18) — propose half of "regenerate this module";
     * nothing changes until {@link #updateModule} is called with the (possibly edited) result.
     */
    @Transactional(readOnly = true)
    public GenerateRoadmapResponse.ProposedModule regenerateModuleScope(Long roadmapId, Long moduleId) {
        return generationService.regenerateModuleScope(roadmapId, moduleId);
    }

    /** Apply an edited module title/scope (Phase 18) — the accept half of {@link #regenerateModuleScope}. */
    @Transactional
    public void updateModule(Long roadmapId, Long moduleId, String title, String scope) {
        structureService.updateModule(roadmapId, moduleId, title, scope);
    }

    /**
     * Draft one new module to insert into this roadmap's outline (Phase 18) — propose half of
     * "insert a module here"; nothing changes until {@link #insertModule} is called.
     */
    @Transactional(readOnly = true)
    public GenerateRoadmapResponse.ProposedModule proposeNewModule(Long roadmapId) {
        return generationService.proposeNewModule(roadmapId);
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
    public RoadmapGenerationService.SubtopicModuleProposal proposeSubtopicModule(Long roadmapId, String focusGoal) {
        return generationService.proposeSubtopicModule(roadmapId, focusGoal);
    }

    /** Insert a new, empty module at {@code position} (Phase 18) — accept half of {@link #proposeNewModule}. */
    @Transactional
    public Entry insertModule(Long roadmapId, String title, String scope, Integer position) {
        return structureService.insertModule(roadmapId, title, scope, position);
    }

    /**
     * Redraft every not-yet-expanded module given real progress so far (Phase 18) — "replan
     * remaining modules." Propose half; nothing changes until {@link #applyReplan} is called
     * with the (possibly edited) result. Each item carries its module's real id so accepting it
     * doesn't need any index translation.
     */
    @Transactional(readOnly = true)
    public List<ReplanModuleItem> replanRemainingModules(Long roadmapId) {
        return generationService.replanRemainingModules(roadmapId);
    }

    /** Apply an accepted replan (Phase 18) — the accept half of {@link #replanRemainingModules}. */
    @Transactional
    public void applyReplan(Long roadmapId, List<ReplanModuleItem> modules) {
        structureService.applyReplan(roadmapId, modules);
    }

    /** How many parents up to the root roadmap (root itself is depth 0). */
    int depthOf(Entry entry) {
        return queryService.depthOf(entry);
    }

    /**
     * True when {@code stepId} is already as deep as the nesting cap allows, so breaking it down
     * further would be rejected (Phase 20) — checked early (before spending an AI call on a draft
     * that could never be applied) as well as enforced again in {@link #splitStep} itself.
     */
    @Transactional(readOnly = true)
    public boolean isAtMaxStepDepth(Long stepId) {
        return queryService.isAtMaxStepDepth(stepId);
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
        return queryService.domainOf(nodeId);
    }

    /**
     * Every leaf {@code roadmap_step} anywhere in this roadmap's tree — modules and steps-with-
     * substeps are containers, not leaves, and are skipped. Callers that need "the real units of
     * work" (e.g. review's stalled-step scan) should use this instead of {@link #stepsOf}, which
     * only returns direct children and, for a nested roadmap, that's module entries, not steps.
     */
    @Transactional(readOnly = true)
    public List<Entry> leafStepsOf(Long roadmapId) {
        return queryService.leafStepsOf(roadmapId);
    }

    @Transactional
    public Entry create(CreateRoadmapRequest req) {
        return structureService.create(req);
    }

    /**
     * The 2–4 "what this covers" bullets for a step (Phase 7.5 deep view). Generated on first
     * open and cached in the step's content, so re-opening is instant and free. Throws
     * {@link IllegalStateException} if the AI can't summarize it right now.
     */
    @Transactional
    public List<String> stepCovers(Long stepId) {
        return generationService.stepCovers(stepId);
    }

    /** A roadmap's steps in order. */
    @Transactional(readOnly = true)
    public List<Entry> stepsOf(Long roadmapId) {
        return queryService.stepsOf(roadmapId);
    }

    /**
     * The one-time CAREER completion reflection (RB-4.7): checked after a step is marked done,
     * cheap enough to call every time — a no-op unless this roadmap is {@code tier: "CAREER"},
     * every leaf step is DONE, and it hasn't already reflected once. {@code null} means nothing
     * new to show (not complete yet, not CAREER, already reflected, or the AI call failed).
     */
    @Transactional
    public String checkCareerCompletion(Long roadmapId) {
        return structureService.checkCareerCompletion(roadmapId);
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
        structureService.syncStepCompletion(stepId);
    }

    /**
     * Active top-level roadmaps (archived excluded), newest first. Child roadmaps (modules,
     * Phase 13) have a parent and are shown inside their root, never as their own list entry.
     */
    @Transactional(readOnly = true)
    public List<Entry> listRoadmaps() {
        return queryService.listRoadmaps();
    }

    /** Archived top-level roadmaps only, newest first (the Archive view). */
    @Transactional(readOnly = true)
    public List<Entry> listArchivedRoadmaps() {
        return queryService.listArchivedRoadmaps();
    }

    /**
     * Archive or unarchive a roadmap (Phase 12): archiving drops it out of the main list into
     * the Archive view without losing it; unarchiving restores it. Nothing is deleted.
     */
    @Transactional
    public Entry setArchived(Long roadmapId, boolean archived) {
        return structureService.setArchived(roadmapId, archived);
    }

    /** Delete a whole roadmap and every step under it (Phase 12). Not reversible. */
    @Transactional
    public void deleteRoadmap(Long roadmapId) {
        structureService.deleteRoadmap(roadmapId);
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
        return retierService.reTier(roadmapId, request);
    }

    /** Confirm a previously-returned re-tier proposal (RB-2.5), applying the founder's groups/order. */
    @Transactional
    public ReTierResponse applyReTierProposal(Long roadmapId, ApplyReTierProposalRequest request) {
        return retierService.applyReTierProposal(roadmapId, request);
    }

    @Transactional(readOnly = true)
    public Entry getRoadmap(Long id) {
        return queryService.getRoadmap(id);
    }

    /**
     * Reorder a roadmap's steps to match {@code orderedStepIds} exactly (every current
     * step, no more, no less) — a partial or mismatched list is rejected rather than
     * silently dropping steps.
     */
    @Transactional
    public void reorderSteps(Long roadmapId, List<Long> orderedStepIds) {
        structureService.reorderSteps(roadmapId, orderedStepIds);
    }

    /**
     * Insert a new step at {@code position} (0-based), shifting later steps down.
     * A null or out-of-range position appends to the end.
     */
    @Transactional
    public Entry insertStep(Long roadmapId, String text, Integer position) {
        return structureService.insertStep(roadmapId, text, position);
    }

    /**
     * Break one step into smaller SUBSTEPS underneath it (Phase 4, reshaped by Phase 13's tree
     * model) — the "break this step down" restructuring. The original step stays (now a
     * container, like a module); the substeps are what's actually worked through.
     */
    @Transactional
    public void splitStep(Long roadmapId, Long stepId, List<CreateRoadmapRequest.DraftStepInput> draftSteps) {
        structureService.splitStep(roadmapId, stepId, draftSteps);
    }

    /**
     * Insert a prerequisite step immediately before {@code stepId}, as its sibling under
     * whichever parent it actually lives under (root roadmap, a module, or another step's
     * substeps), and record it as that step's real prerequisite (depends_on) — the "something's
     * missing first" restructuring (Phase 4).
     */
    @Transactional
    public Entry addPrerequisite(Long roadmapId, Long stepId, String prerequisiteText) {
        return structureService.addPrerequisite(roadmapId, stepId, prerequisiteText);
    }

    /**
     * "Promote back up" — flatten (Phase 20): delete a container step's substeps, reverting it
     * to a plain leaf.
     */
    @Transactional
    public void flattenStep(Long roadmapId, Long stepId) {
        structureService.flattenStep(roadmapId, stepId);
    }

    /**
     * "Promote back up" — graduate (Phase 20): reparent one substep to become a sibling of its
     * current parent instead of nested beneath it.
     */
    @Transactional
    public void graduateStep(Long roadmapId, Long stepId) {
        structureService.graduateStep(roadmapId, stepId);
    }

    /**
     * Delete a step anywhere in the roadmap's tree and close the order_index gap under its
     * parent (Phase 13). Any substeps under it go too, via the parent FK's ON DELETE CASCADE.
     */
    @Transactional
    public void deleteStep(Long roadmapId, Long stepId) {
        structureService.deleteStep(roadmapId, stepId);
    }
}
