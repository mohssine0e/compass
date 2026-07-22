package com.compass.app.resource;

import com.compass.app.resource.dto.SuggestResourcesRequest;
import com.compass.app.roadmap.dto.GenerateRoadmapResponse.ProposedResource;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/resources")
public class ResourceController {

    private final ResourceService service;

    public ResourceController(ResourceService service) {
        this.service = service;
    }

    /**
     * Resources for an already-drafted, not-yet-persisted batch of steps — the follow-up call for
     * a proposal shown to the founder before resources were ready (flat-goal proposals,
     * reformulate/resurfacing break-downs). Returns a list aligned by index to
     * {@code stepTexts} (empty list per step with nothing fitting).
     */
    @PostMapping("/suggest")
    public List<List<ProposedResource>> suggest(@RequestBody SuggestResourcesRequest request) {
        return service.suggestResourcesFor(request.scope(), request.stepTexts(), request.roadmapId());
    }
}
