package com.compass.app.roadmap;

import com.compass.app.entry.Entry;
import com.compass.app.roadmap.dto.ArchiveRoadmapRequest;
import com.compass.app.roadmap.dto.CreateRoadmapRequest;
import com.compass.app.roadmap.dto.GenerateRoadmapRequest;
import com.compass.app.roadmap.dto.GenerateRoadmapResponse;
import com.compass.app.roadmap.dto.InsertStepRequest;
import com.compass.app.roadmap.dto.ReorderStepsRequest;
import com.compass.app.roadmap.dto.RoadmapResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/roadmaps")
public class RoadmapController {

    private final RoadmapService service;

    public RoadmapController(RoadmapService service) {
        this.service = service;
    }

    /** Create a roadmap with an ordered list of manually-written steps. */
    @PostMapping
    public ResponseEntity<RoadmapResponse> create(@RequestBody CreateRoadmapRequest request) {
        Entry roadmap = service.create(request);
        RoadmapResponse body = RoadmapResponse.of(roadmap, service::stepsOf);
        return ResponseEntity.status(HttpStatus.CREATED).body(body);
    }

    /**
     * One turn of AI drafting. Body {goal} with no clarifications → clarifying questions;
     * body {goal, clarifications:[{question, answer}]} → an editable step proposal. 503 when
     * no AI provider is configured/reachable, so the UI falls back to the manual form.
     */
    @PostMapping("/generate")
    public GenerateRoadmapResponse generate(@RequestBody GenerateRoadmapRequest request) {
        return service.generate(request);
    }

    /** Active roadmaps with their steps and progress, newest first (archived excluded). */
    @GetMapping
    public List<RoadmapResponse> list() {
        return service.listRoadmaps().stream()
                .map(r -> RoadmapResponse.of(r, service::stepsOf))
                .toList();
    }

    /** Archived roadmaps only — the Archive view (Phase 12). */
    @GetMapping("/archived")
    public List<RoadmapResponse> listArchived() {
        return service.listArchivedRoadmaps().stream()
                .map(r -> RoadmapResponse.of(r, service::stepsOf))
                .toList();
    }

    /** One roadmap with its tree of nodes and where-am-I progress. */
    @GetMapping("/{id}")
    public RoadmapResponse get(@PathVariable Long id) {
        Entry roadmap = service.getRoadmap(id);
        return RoadmapResponse.of(roadmap, service::stepsOf);
    }

    /** Insert a new step. Body is {text, position?} — appended when position is omitted. */
    @PostMapping("/{id}/steps")
    public ResponseEntity<RoadmapResponse> insertStep(@PathVariable Long id,
                                                        @RequestBody InsertStepRequest request) {
        service.insertStep(id, request.text(), request.position());
        Entry roadmap = service.getRoadmap(id);
        RoadmapResponse body = RoadmapResponse.of(roadmap, service::stepsOf);
        return ResponseEntity.status(HttpStatus.CREATED).body(body);
    }

    /** Reorder a roadmap's steps. Body is the full ordered list of step ids. */
    @PutMapping("/{id}/steps/order")
    public RoadmapResponse reorderSteps(@PathVariable Long id,
                                         @RequestBody ReorderStepsRequest request) {
        service.reorderSteps(id, request.stepIds());
        Entry roadmap = service.getRoadmap(id);
        return RoadmapResponse.of(roadmap, service::stepsOf);
    }

    /** Delete a step from a roadmap. */
    @DeleteMapping("/{id}/steps/{stepId}")
    public RoadmapResponse deleteStep(@PathVariable Long id, @PathVariable Long stepId) {
        service.deleteStep(id, stepId);
        Entry roadmap = service.getRoadmap(id);
        return RoadmapResponse.of(roadmap, service::stepsOf);
    }

    /** Archive or unarchive a whole roadmap. Body is {archived}. */
    @PutMapping("/{id}/archive")
    public RoadmapResponse archive(@PathVariable Long id, @RequestBody ArchiveRoadmapRequest request) {
        Entry roadmap = service.setArchived(id, request.archived());
        return RoadmapResponse.of(roadmap, service::stepsOf);
    }

    /** Delete a whole roadmap and its steps. */
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        service.deleteRoadmap(id);
        return ResponseEntity.noContent().build();
    }

    /** "What this step covers" bullets for the deep view — generated once, then cached. */
    @PostMapping("/steps/{stepId}/covers")
    public java.util.Map<String, Object> stepCovers(@PathVariable Long stepId) {
        return java.util.Map.of("covers", service.stepCovers(stepId));
    }
}
