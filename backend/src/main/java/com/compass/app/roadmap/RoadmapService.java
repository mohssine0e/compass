package com.compass.app.roadmap;

import com.compass.app.entry.Entry;
import com.compass.app.entry.EntryRepository;
import com.compass.app.entry.EntryStatus;
import com.compass.app.entry.EntryType;
import com.compass.app.roadmap.dto.CreateRoadmapRequest;
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

    public RoadmapService(EntryRepository repository) {
        this.repository = repository;
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
