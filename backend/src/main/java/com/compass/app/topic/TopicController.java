package com.compass.app.topic;

import com.compass.app.ai.TopicAiService;
import com.compass.app.topic.dto.ApplyAdditionRequest;
import com.compass.app.topic.dto.CanonicalTopicResponse;
import com.compass.app.topic.dto.SuggestAdditionRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Canonical Topic Matching's topic evolution endpoints (RB-3.10). */
@RestController
@RequestMapping("/topics")
public class TopicController {

    private final TopicService service;

    public TopicController(TopicService service) {
        this.service = service;
    }

    /** The canonical topic a given roadmap was created as/from, or null if it doesn't have one. */
    @GetMapping
    public CanonicalTopicResponse forRoadmap(@RequestParam Long roadmapId) {
        return CanonicalTopicResponse.from(service.forRoadmap(roadmapId));
    }

    /** Draft the specific edit a founder's suggestion implies — nothing applied yet. */
    @PostMapping("/{id}/suggest-addition")
    public TopicAiService.AdditionProposal suggestAddition(@PathVariable Long id,
                                                           @RequestBody SuggestAdditionRequest request) {
        return service.proposeAddition(id, request.suggestion());
    }

    /** Apply a confirmed addition. */
    @PostMapping("/{id}/apply-addition")
    public CanonicalTopicResponse applyAddition(@PathVariable Long id, @RequestBody ApplyAdditionRequest request) {
        return CanonicalTopicResponse.from(service.applyAddition(id, request.field(), request.value()));
    }
}
