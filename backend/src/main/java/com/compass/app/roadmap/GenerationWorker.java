package com.compass.app.roadmap;

import com.compass.app.events.EventService;
import com.compass.app.roadmap.dto.GenerateRoadmapRequest;
import com.compass.app.roadmap.dto.GenerateRoadmapResponse;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

/**
 * The actual background work for a {@link GenerationJob} (Phase 18) — a separate bean from
 * {@link GenerationJobService} so {@code @Async} genuinely applies: Spring's AOP proxy only
 * intercepts calls that come from OUTSIDE the bean, so an {@code @Async} method called on
 * {@code this} from within the same class would silently run synchronously instead.
 */
@Component
class GenerationWorker {

    private final RoadmapService roadmapService;
    private final EventService events;

    GenerationWorker(RoadmapService roadmapService, EventService events) {
        this.roadmapService = roadmapService;
        this.events = events;
    }

    @Async
    public void run(GenerationJob job, GenerateRoadmapRequest req) {
        try {
            GenerateRoadmapResponse result = roadmapService.generate(req, job::setStage);
            job.complete(result);
        } catch (RuntimeException ex) {
            // A brief, honest note (CLAUDE.md: system_events stays short, not a stack trace) —
            // the same pattern every other AI-call failure in this codebase already uses.
            events.aiWarning("provider_error", "Roadmap generation job failed: " + ex.getMessage(), null);
            job.fail(friendlyMessage(ex));
        }
    }

    private static String friendlyMessage(RuntimeException ex) {
        if (ex instanceof IllegalArgumentException || ex instanceof IllegalStateException) {
            return ex.getMessage();
        }
        return "Drafting is unavailable right now — write the steps yourself.";
    }
}
