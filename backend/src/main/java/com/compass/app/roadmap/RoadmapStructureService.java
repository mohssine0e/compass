package com.compass.app.roadmap;

import com.compass.app.ai.ReviewAiService;
import com.compass.app.entry.Entry;
import com.compass.app.entry.EntryRepository;
import com.compass.app.entry.EntryStatus;
import com.compass.app.entry.EntryType;
import com.compass.app.resource.ResourceService;
import com.compass.app.roadmap.dto.CreateRoadmapRequest;
import com.compass.app.roadmap.dto.ReplanModuleItem;
import com.compass.app.topic.CanonicalTopic;
import com.compass.app.topic.CanonicalTopicRepository;
import com.compass.app.ai.EmbeddingService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

/**
 * Creating and editing a roadmap's own tree structure (V3-4.1's split of the former {@code
 * RoadmapService} god-file — see {@code TASKS_v3.md}): everything that mutates the {@code entries}
 * table directly, with no AI call of its own — create/insert/reorder/split/flatten/graduate/
 * delete, plus the completion-sync mechanics. Pure move, no behaviour change — {@link
 * com.compass.app.roadmap.RoadmapServiceBehaviorTest}'s coverage of these methods moved with them
 * into {@code RoadmapStructureServiceTest}.
 *
 * <p>{@link #createDraftSteps} is package-private rather than private: it's the one method here
 * a Generation-seam method ({@code RoadmapService.retrySkeletonModule}, not yet extracted) also
 * needs — three of its four call sites are structural (create/addStepsToModule/splitStep), so it
 * lives here, with the one AI-drafting caller reaching in rather than the other way around.
 */
@Service
public class RoadmapStructureService {

    private final EntryRepository repository;
    private final RoadmapQueryService queryService;
    private final CanonicalTopicRepository canonicalTopics;
    private final EmbeddingService embeddings;
    private final ReviewAiService reviewAi;

    public RoadmapStructureService(EntryRepository repository, RoadmapQueryService queryService,
                                   CanonicalTopicRepository canonicalTopics, EmbeddingService embeddings,
                                   ReviewAiService reviewAi) {
        this.repository = repository;
        this.queryService = queryService;
        this.canonicalTopics = canonicalTopics;
        this.embeddings = embeddings;
        this.reviewAi = reviewAi;
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
     * under the root, with no steps yet. Steps are added later via module expansion +
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
    void createDraftSteps(Long roadmapId, List<CreateRoadmapRequest.DraftStepInput> draftSteps) {
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

    /** Accept a module's expanded steps (Phase 13) — same shape and validation as roadmap steps. */
    @Transactional
    public void addStepsToModule(Long roadmapId, Long moduleId,
                                  List<CreateRoadmapRequest.DraftStepInput> draftSteps) {
        queryService.requireModule(roadmapId, moduleId);
        createDraftSteps(moduleId, draftSteps);
        repository.touchUpdatedAt(roadmapId, Instant.now());
    }

    /** Apply an edited module title/scope (Phase 18) — the accept half of module-scope redraft. */
    @Transactional
    public void updateModule(Long roadmapId, Long moduleId, String title, String scope) {
        Entry module = queryService.requireModule(roadmapId, moduleId);
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

    /** Insert a new, empty module at {@code position} (Phase 18) — accept half of "propose a new module". */
    @Transactional
    public Entry insertModule(Long roadmapId, String title, String scope, Integer position) {
        queryService.getRoadmap(roadmapId);
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

    /** Apply an accepted replan (Phase 18) — the accept half of "replan remaining modules". */
    @Transactional
    public void applyReplan(Long roadmapId, List<ReplanModuleItem> modules) {
        for (ReplanModuleItem m : modules) {
            updateModule(roadmapId, m.moduleId(), m.title(), m.scope());
        }
    }

    /**
     * Reorder a roadmap's steps to match {@code orderedStepIds} exactly (every current
     * step, no more, no less) — a partial or mismatched list is rejected rather than
     * silently dropping steps.
     */
    @Transactional
    public void reorderSteps(Long roadmapId, List<Long> orderedStepIds) {
        List<Entry> steps = queryService.stepsOf(roadmapId);
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
        queryService.getRoadmap(roadmapId);
        String trimmed = text != null ? text.trim() : "";
        if (trimmed.isEmpty()) {
            throw new IllegalArgumentException("A step needs some text.");
        }

        List<Entry> steps = queryService.stepsOf(roadmapId);
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
        if (queryService.depthOf(original) >= RoadmapQueryService.MAX_STEP_DEPTH) {
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
     * precondition over a destructive-action confirm dialog: cheap to check, and never silently
     * loses something the founder actually did.
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
        if (substeps.stream().anyMatch(RoadmapStructureService::hasRealProgress)) {
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

    /**
     * Archive or unarchive a roadmap (Phase 12): archiving drops it out of the main list into
     * the Archive view without losing it; unarchiving restores it. Nothing is deleted.
     */
    @Transactional
    public Entry setArchived(Long roadmapId, boolean archived) {
        Entry roadmap = queryService.getRoadmap(roadmapId);
        roadmap.setStatus(archived ? EntryStatus.ARCHIVED : EntryStatus.IN_MOTION);
        return repository.save(roadmap);
    }

    /** Delete a whole roadmap and every step under it (Phase 12). Not reversible. */
    @Transactional
    public void deleteRoadmap(Long roadmapId) {
        queryService.getRoadmap(roadmapId); // 404 if it isn't a roadmap
        repository.deleteAll(queryService.stepsOf(roadmapId));
        repository.deleteById(roadmapId);
    }

    /**
     * The one-time CAREER completion reflection (RB-4.7): checked after a step is marked done,
     * cheap enough to call every time — a no-op unless this roadmap is {@code tier: "CAREER"},
     * every leaf step is DONE, and it hasn't already reflected once. {@code null} means nothing
     * new to show (not complete yet, not CAREER, already reflected, or the AI call failed).
     */
    @Transactional
    public String checkCareerCompletion(Long roadmapId) {
        Entry roadmap = queryService.getRoadmap(roadmapId);
        if (!"CAREER".equals(storedTier(roadmap))
                || roadmap.getContent() != null && roadmap.getContent().get("completionReflection") != null) {
            return null;
        }
        List<Entry> leaves = queryService.leafStepsOf(roadmapId);
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

    private static String stringOf(Entry entry, String key) {
        Object value = entry != null && entry.getContent() != null ? entry.getContent().get(key) : null;
        return value instanceof String s ? s : null;
    }

    /** The roadmap's stored tier (RB-2.3), or null if never classified. */
    private static String storedTier(Entry roadmap) {
        Object raw = roadmap.getContent() != null ? roadmap.getContent().get("tier") : null;
        return raw instanceof String s ? s : null;
    }
}
