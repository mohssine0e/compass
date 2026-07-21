package com.compass.app.roadmap;

import com.compass.app.entry.Entry;
import com.compass.app.roadmap.dto.AddModuleStepsRequest;
import com.compass.app.roadmap.dto.ArchiveRoadmapRequest;
import com.compass.app.roadmap.dto.CreateRoadmapRequest;
import com.compass.app.roadmap.dto.GenerateRoadmapRequest;
import com.compass.app.roadmap.dto.GenerateRoadmapResponse;
import com.compass.app.roadmap.dto.GenerationJobResponse;
import com.compass.app.roadmap.dto.InsertModuleRequest;
import com.compass.app.roadmap.dto.InsertStepRequest;
import com.compass.app.roadmap.dto.ReorderStepsRequest;
import com.compass.app.roadmap.dto.RoadmapResponse;
import com.compass.app.roadmap.dto.UpdateModuleRequest;
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
import java.util.Map;

@RestController
@RequestMapping("/roadmaps")
public class RoadmapController {

    private final RoadmapService service;
    private final GenerationJobService jobs;

    public RoadmapController(RoadmapService service, GenerationJobService jobs) {
        this.service = service;
        this.jobs = jobs;
    }

    /** Create a roadmap with an ordered list of manually-written steps. */
    @PostMapping
    public ResponseEntity<RoadmapResponse> create(@RequestBody CreateRoadmapRequest request) {
        Entry roadmap = service.create(request);
        RoadmapResponse body = RoadmapResponse.of(roadmap, service::stepsOf);
        return ResponseEntity.status(HttpStatus.CREATED).body(body);
    }

    /**
     * Start one turn of AI drafting in the background (Phase 18) — body {goal} with no
     * clarifications, or {goal, clarifications:[{question, answer}]} once answered. Returns
     * immediately with a job id; poll {@link #generationJob} for progress and the eventual
     * result, rather than blocking on a single AI call that can take up to a minute on the
     * free-tier tertiary provider. The job survives independently of this request, so it can be
     * started on one device and checked from another.
     */
    @PostMapping("/generate/start")
    public Map<String, String> startGeneration(@RequestBody GenerateRoadmapRequest request) {
        return Map.of("jobId", jobs.start(request));
    }

    /**
     * Poll a drafting job's progress. {@code status} is PENDING/DONE/FAILED; {@code stage} names
     * what's currently running while PENDING (CLARIFYING/ASSESSING/DRAFTING/FINDING_RESOURCES);
     * {@code result} is the same shape {@code /generate} used to return, once DONE; {@code error}
     * is set (the same message the old synchronous 503 used to carry) once FAILED.
     */
    @GetMapping("/generate/jobs/{jobId}")
    public GenerationJobResponse generationJob(@PathVariable String jobId) {
        GenerationJob job = jobs.get(jobId);
        return new GenerationJobResponse(
                job.status().name(),
                job.stage() == null ? null : job.stage().name(),
                job.result(),
                job.error());
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

    /**
     * Draft steps for one module of this roadmap (Phase 13) — grounded on the module's own
     * scope, deduped against resources already used elsewhere in the roadmap. Nothing persisted;
     * accept via {@link #addModuleSteps}.
     */
    @PostMapping("/{id}/modules/{moduleId}/expand")
    public GenerateRoadmapResponse expandModule(@PathVariable Long id, @PathVariable Long moduleId) {
        return service.expandModule(id, moduleId);
    }

    /** Accept a module's expanded steps. Body is {draftSteps: [...]}, same shape as roadmap creation. */
    @PostMapping("/{id}/modules/{moduleId}/steps")
    public ResponseEntity<RoadmapResponse> addModuleSteps(@PathVariable Long id, @PathVariable Long moduleId,
                                                           @RequestBody AddModuleStepsRequest request) {
        service.addStepsToModule(id, moduleId, request.draftSteps());
        Entry roadmap = service.getRoadmap(id);
        RoadmapResponse body = RoadmapResponse.of(roadmap, service::stepsOf);
        return ResponseEntity.status(HttpStatus.CREATED).body(body);
    }

    /**
     * Redraft one module's title/scope (Phase 18) — "regenerate this module" when the outline is
     * right but one area isn't. Nothing changes yet; accept via {@link #updateModule}.
     */
    @PostMapping("/{id}/modules/{moduleId}/regenerate-scope")
    public GenerateRoadmapResponse.ProposedModule regenerateModuleScope(@PathVariable Long id,
                                                                          @PathVariable Long moduleId) {
        return service.regenerateModuleScope(id, moduleId);
    }

    /** Apply an edited module title/scope. Body is {title, scope}. */
    @PutMapping("/{id}/modules/{moduleId}")
    public RoadmapResponse updateModule(@PathVariable Long id, @PathVariable Long moduleId,
                                         @RequestBody UpdateModuleRequest request) {
        service.updateModule(id, moduleId, request.title(), request.scope());
        Entry roadmap = service.getRoadmap(id);
        return RoadmapResponse.of(roadmap, service::stepsOf);
    }

    /**
     * Draft one new module to insert into this roadmap (Phase 18) — "insert a module here" when
     * the user notices a real gap. Nothing changes yet; accept via {@link #insertModule}.
     */
    @PostMapping("/{id}/modules/insert-proposal")
    public GenerateRoadmapResponse.ProposedModule proposeNewModule(@PathVariable Long id) {
        return service.proposeNewModule(id);
    }

    /** Insert an accepted new module. Body is {title, scope, position?} — appended if omitted. */
    @PostMapping("/{id}/modules")
    public ResponseEntity<RoadmapResponse> insertModule(@PathVariable Long id,
                                                          @RequestBody InsertModuleRequest request) {
        service.insertModule(id, request.title(), request.scope(), request.position());
        Entry roadmap = service.getRoadmap(id);
        RoadmapResponse body = RoadmapResponse.of(roadmap, service::stepsOf);
        return ResponseEntity.status(HttpStatus.CREATED).body(body);
    }

    /** "What this step covers" bullets for the deep view — generated once, then cached. */
    @PostMapping("/steps/{stepId}/covers")
    public java.util.Map<String, Object> stepCovers(@PathVariable Long stepId) {
        return java.util.Map.of("covers", service.stepCovers(stepId));
    }
}
