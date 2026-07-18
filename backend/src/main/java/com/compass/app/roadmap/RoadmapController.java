package com.compass.app.roadmap;

import com.compass.app.entry.Entry;
import com.compass.app.roadmap.dto.CreateRoadmapRequest;
import com.compass.app.roadmap.dto.RoadmapResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

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
}
