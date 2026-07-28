package com.compass.app.resource;

import com.compass.app.resource.dto.EnrichResourceRequest;
import com.compass.app.resource.dto.EnrichmentResponse;
import com.compass.app.resource.dto.SuggestResourcesRequest;
import com.compass.app.roadmap.dto.GenerateRoadmapResponse.ProposedResource;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/resources")
public class ResourceController {

    private final ResourceService service;
    private final ResourceEnrichmentService enrichmentService;

    public ResourceController(ResourceService service, ResourceEnrichmentService enrichmentService) {
        this.service = service;
        this.enrichmentService = enrichmentService;
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

    /**
     * RES-5's lazy trigger: a cache hit (RES-2's Exa reuse, or an already-enriched resource)
     * returns instantly; a miss runs the fallback fetch+summarize (RES-3) or transcript lookup
     * (RES-4) inline — this is deliberately a synchronous call, not a job+polling one (see
     * RESSOURCE_BRAIN_TASKS.md decision #4: one fetch + one fast-tier call resolves in a few
     * seconds, not the 30-90s a real job pipeline exists for). 204 when nothing could be
     * produced — the deep view then shows the plain link with no fabricated pointer.
     */
    @PostMapping("/enrich")
    public ResponseEntity<EnrichmentResponse> enrich(@RequestBody EnrichResourceRequest request) {
        EnrichmentResponse result = enrichmentService.enrich(request.resourceUrl(), request.stepTopic());
        return result == null ? ResponseEntity.noContent().build() : ResponseEntity.ok(result);
    }
}
