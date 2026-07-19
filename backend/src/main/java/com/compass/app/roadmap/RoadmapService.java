package com.compass.app.roadmap;

import com.compass.app.ai.RoadmapAiService;
import com.compass.app.entry.Entry;
import com.compass.app.entry.EntryRepository;
import com.compass.app.entry.EntryStatus;
import com.compass.app.entry.EntryType;
import com.compass.app.profile.ProfileContext;
import com.compass.app.profile.ProfileService;
import com.compass.app.roadmap.dto.CreateRoadmapRequest;
import com.compass.app.roadmap.dto.GenerateRoadmapRequest;
import com.compass.app.roadmap.dto.GenerateRoadmapResponse;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

/**
 * Roadmaps are just entries: a {@code roadmap} row plus ordered {@code roadmap_step}
 * children pointing at it via parent_id (see CLAUDE.md Section 4). No separate tables.
 */
@Service
public class RoadmapService {

    private final EntryRepository repository;
    private final RoadmapAiService roadmapAi;
    private final ProfileService profileService;

    public RoadmapService(EntryRepository repository, RoadmapAiService roadmapAi,
                          ProfileService profileService) {
        this.repository = repository;
        this.roadmapAi = roadmapAi;
        this.profileService = profileService;
    }

    /**
     * One turn of the AI drafting flow (Phase 4). With no clarifications yet, returns the
     * clarifying questions to ask first; once they're answered, returns a proposed step
     * breakdown the user edits and owns. Nothing is persisted until the user creates the
     * roadmap the normal way. Throws {@link IllegalStateException} when no AI provider can
     * serve the request, so the caller can fall back to writing steps by hand.
     */
    public GenerateRoadmapResponse generate(GenerateRoadmapRequest req) {
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
            List<String> questions = roadmapAi.clarifyingQuestions(goal, profileContext);
            if (questions == null) {
                throw new IllegalStateException(
                        "Drafting is unavailable right now — write the steps yourself.");
            }
            return GenerateRoadmapResponse.needsClarification(questions);
        }

        RoadmapAiService.RoadmapDraft draft = roadmapAi.proposeRoadmap(
                goal, formatClarifications(req.clarifications()), profileContext);
        if (draft == null) {
            throw new IllegalStateException(
                    "Drafting is unavailable right now — write the steps yourself.");
        }
        return GenerateRoadmapResponse.proposal(draft.title(), draft.steps(), draft.skipped());
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

        Entry roadmap = new Entry();
        roadmap.setType(EntryType.ROADMAP);
        roadmap.setStatus(EntryStatus.IN_MOTION);
        roadmap.setContent(content);
        roadmap = repository.save(roadmap);

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

        return roadmap;
    }

    /** A roadmap's steps in order. */
    @Transactional(readOnly = true)
    public List<Entry> stepsOf(Long roadmapId) {
        return repository.findByParentIdOrderByOrderIndexAsc(roadmapId);
    }

    /** All roadmaps, newest first. */
    @Transactional(readOnly = true)
    public List<Entry> listRoadmaps() {
        return repository.findByTypeOrderByCreatedAtDesc(EntryType.ROADMAP);
    }

    @Transactional(readOnly = true)
    public Entry getRoadmap(Long id) {
        Entry entry = repository.findById(id)
                .filter(e -> e.getType() == EntryType.ROADMAP)
                .orElseThrow(() -> new java.util.NoSuchElementException(
                        "No roadmap with id " + id));
        return entry;
    }

    /** Load each roadmap together with its ordered steps. */
    @Transactional(readOnly = true)
    public List<RoadmapWithSteps> listRoadmapsWithSteps() {
        List<RoadmapWithSteps> result = new ArrayList<>();
        for (Entry roadmap : listRoadmaps()) {
            result.add(new RoadmapWithSteps(roadmap, stepsOf(roadmap.getId())));
        }
        return result;
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
     * Replace one step with several smaller ones at the same position — the "break this step
     * down" restructuring (Phase 4). The replacements start fresh (captured), so breaking a
     * step down re-opens it as concrete sub-steps.
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

        List<Entry> steps = stepsOf(roadmapId);
        int index = indexOfStep(steps, stepId, roadmapId);
        Entry original = steps.remove(index);
        repository.delete(original);

        List<Entry> replacementSteps = new ArrayList<>();
        for (String text : clean) {
            replacementSteps.add(newStep(roadmapId, text));
        }
        steps.addAll(index, replacementSteps);
        reindexAndSave(steps);
        repository.touchUpdatedAt(roadmapId, Instant.now());
    }

    /**
     * Insert a prerequisite step immediately before {@code stepId} and record it as that step's
     * real prerequisite (depends_on) — the "something's missing first" restructuring (Phase 4).
     */
    @Transactional
    public Entry addPrerequisite(Long roadmapId, Long stepId, String prerequisiteText) {
        String text = prerequisiteText != null ? prerequisiteText.trim() : "";
        if (text.isEmpty()) {
            throw new IllegalArgumentException("A prerequisite needs some text.");
        }

        List<Entry> steps = stepsOf(roadmapId);
        int index = indexOfStep(steps, stepId, roadmapId);
        Entry target = steps.get(index);

        Entry prerequisite = newStep(roadmapId, text);
        steps.add(index, prerequisite);
        reindexAndSave(steps); // assigns the new step its id
        target.setDependsOn(prerequisite.getId());
        repository.save(target);
        repository.touchUpdatedAt(roadmapId, Instant.now());
        return prerequisite;
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

    /** Delete a step and close the order_index gap it leaves behind. */
    @Transactional
    public void deleteStep(Long roadmapId, Long stepId) {
        List<Entry> steps = stepsOf(roadmapId);
        Entry toDelete = steps.stream()
                .filter(s -> s.getId().equals(stepId))
                .findFirst()
                .orElseThrow(() -> new java.util.NoSuchElementException(
                        "No step " + stepId + " on roadmap " + roadmapId));

        steps.remove(toDelete);
        repository.delete(toDelete);
        reindexAndSave(steps);
        repository.touchUpdatedAt(roadmapId, Instant.now());
    }

    /** Reassigns order_index 0..n-1 to match list order, then persists all of them. */
    private void reindexAndSave(List<Entry> orderedSteps) {
        for (int i = 0; i < orderedSteps.size(); i++) {
            orderedSteps.get(i).setOrderIndex(i);
        }
        repository.saveAll(orderedSteps);
    }

    /** Small carrier so controllers can map to the response DTO. */
    public record RoadmapWithSteps(Entry roadmap, List<Entry> steps) {
    }
}
