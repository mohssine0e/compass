package com.compass.app.assistant;

import com.compass.app.assistant.dto.ExplainRequest;
import com.compass.app.events.EventService;
import com.compass.app.notifications.NotificationService;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

/**
 * The actual background work for an {@link ExplainJob} — a separate bean from
 * {@link ExplainJobService} so {@code @Async} genuinely applies (same self-invocation pitfall
 * {@code GenerationWorker} already documents).
 *
 * <p>Unlike a capture acknowledgment, the explain answer IS the content the founder is waiting
 * on, not an addendum — so completion also pushes a notification carrying the (possibly
 * truncated) answer itself, not just a "ready" line, in case the founder has since navigated
 * away from the card that asked for it.
 */
@Component
class ExplainWorker {

    // A toast is a glance, not a reading pane — long enough to be useful on its own, short enough
    // to still read as a toast rather than a wall of text.
    private static final int NOTIFICATION_MAX_CHARS = 320;

    private final AssistantService assistantService;
    private final NotificationService notifications;
    private final EventService events;

    ExplainWorker(AssistantService assistantService, NotificationService notifications, EventService events) {
        this.assistantService = assistantService;
        this.notifications = notifications;
        this.events = events;
    }

    @Async
    void run(ExplainJob job, ExplainRequest req) {
        try {
            String result = assistantService.explain(req);
            job.complete(result);
            notifications.push(truncate(result), context(req));
        } catch (RuntimeException ex) {
            events.aiWarning("provider_error", "Explain job failed: " + ex.getMessage(), null);
            String message = friendlyMessage(ex);
            job.fail(message);
            notifications.push(message, "danger", context(req));
        }
    }

    private static Map<String, Object> context(ExplainRequest req) {
        Long stepId = req.context() != null ? req.context().stepId() : null;
        return stepId == null ? null : new HashMap<>(Map.of("stepId", stepId));
    }

    private static String truncate(String text) {
        if (text == null || text.length() <= NOTIFICATION_MAX_CHARS) {
            return text;
        }
        return text.substring(0, NOTIFICATION_MAX_CHARS - 1).stripTrailing() + "…";
    }

    private static String friendlyMessage(RuntimeException ex) {
        if (ex instanceof IllegalArgumentException || ex instanceof IllegalStateException) {
            return ex.getMessage();
        }
        return "Couldn't help with that right now.";
    }
}
