package com.compass.app.roadmap;

import com.compass.app.entry.Entry;
import com.compass.app.roadmap.dto.CreateRoadmapRequest;
import com.compass.app.roadmap.dto.ReorderStepsRequest;
import com.compass.app.roadmap.dto.RoadmapResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
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
        RoadmapResponse body = RoadmapResponse.of(roadmap, service.stepsOf(roadmap.getId()));
        return ResponseEntity.status(HttpStatus.CREATED).body(body);
    }

    /** All roadmaps with their steps and progress, newest first. */
    @GetMapping
    public List<RoadmapResponse> list() {
        return service.listRoadmapsWithSteps().stream()
                .map(r -> RoadmapResponse.of(r.roadmap(), r.steps()))
                .toList();
    }

    /** One roadmap with its ordered steps and where-am-I progress. */
    @GetMapping("/{id}")
    public RoadmapResponse get(@PathVariable Long id) {
        Entry roadmap = service.getRoadmap(id);
        return RoadmapResponse.of(roadmap, service.stepsOf(id));
    }

    /** Reorder a roadmap's steps. Body is the full ordered list of step ids. */
    @PutMapping("/{id}/steps/order")
    public RoadmapResponse reorderSteps(@PathVariable Long id,
                                         @RequestBody ReorderStepsRequest request) {
        service.reorderSteps(id, request.stepIds());
        Entry roadmap = service.getRoadmap(id);
        return RoadmapResponse.of(roadmap, service.stepsOf(id));
    }
}
