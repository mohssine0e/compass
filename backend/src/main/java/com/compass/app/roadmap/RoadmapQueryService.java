package com.compass.app.roadmap;

import com.compass.app.entry.Entry;
import com.compass.app.entry.EntryRepository;
import com.compass.app.entry.EntryStatus;
import com.compass.app.entry.EntryType;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Read-only lookups over the roadmap tree (V3-4.1's split of the former {@code RoadmapService}
 * god-file — see {@code TASKS_v3.md}): the roadmap/module/step existence and depth checks every
 * other roadmap collaborator needs, plus the plain listing/query endpoints. Pure moves only, no
 * behaviour change — {@link com.compass.app.roadmap.RoadmapServiceBehaviorTest}'s coverage of
 * these methods moved with them into {@code RoadmapQueryServiceTest}.
 */
@Service
public class RoadmapQueryService {

    // Total tree depth allowed below the roadmap root (Phase 20) — module(1)/step(2)/substep(3),
    // no deeper. Counted from the root, not by role, so a flat roadmap's step(1)/substep(2)
    // naturally gets one more level of break-down room than a nested one, which is fine: the cap
    // is about total nesting, not about labeling every level "module" or "step".
    static final int MAX_STEP_DEPTH = 3;

    private final EntryRepository repository;

    public RoadmapQueryService(EntryRepository repository) {
        this.repository = repository;
    }

    @Transactional(readOnly = true)
    public Entry getRoadmap(Long id) {
        return repository.findById(id)
                .filter(e -> e.getType() == EntryType.ROADMAP)
                .orElseThrow(() -> new java.util.NoSuchElementException(
                        "No roadmap with id " + id));
    }

    Entry requireModule(Long roadmapId, Long moduleId) {
        return repository.findById(moduleId)
                .filter(e -> e.getType() == EntryType.ROADMAP && roadmapId.equals(e.getParentId()))
                .orElseThrow(() -> new java.util.NoSuchElementException(
                        "No module " + moduleId + " on roadmap " + roadmapId));
    }

    /** A roadmap's steps in order. */
    @Transactional(readOnly = true)
    public List<Entry> stepsOf(Long roadmapId) {
        return repository.findByParentIdOrderByOrderIndexAsc(roadmapId);
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

    /** How many parents up to the root roadmap (root itself is depth 0). */
    int depthOf(Entry entry) {
        if (entry.getParentId() == null) {
            return 0;
        }
        // One recursive query instead of a findById per level (V3-3.1).
        return repository.findAncestors(entry.getId()).size();
    }

    /**
     * True when {@code stepId} is already as deep as the nesting cap allows, so breaking it down
     * further would be rejected (Phase 20) — checked early (before spending an AI call on a draft
     * that could never be applied) as well as enforced again in {@code splitStep} itself.
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
     * Ids of this roadmap's not-yet-expanded modules — any direct child module with no steps of
     * its own yet. Used by {@code ModulePrefetchService} (via the controller) to know which
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
}
