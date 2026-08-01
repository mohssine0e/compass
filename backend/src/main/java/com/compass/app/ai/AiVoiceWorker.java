package com.compass.app.ai;

import com.compass.app.entry.Entry;
import com.compass.app.events.EventService;
import com.compass.app.notifications.NotificationService;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Runs {@link AiVoiceService#acknowledge} off the request path — a separate bean from its
 * callers so {@code @Async} genuinely applies (Spring's AOP proxy only intercepts calls from
 * outside the bean; same reasoning {@code GenerationWorker} documents for the generation job).
 *
 * <p>Capture and mark-done used to block the HTTP response on this call (up to ~4 FAST-tier
 * providers, each with their own timeout) even though the entry itself is already saved and
 * safe to show. Now the caller gets the entry back immediately with no acknowledgment, and the
 * real line — once it exists — arrives a few seconds later as a {@link NotificationService}
 * toast instead.
 */
@Component
public class AiVoiceWorker {

    private final AiVoiceService aiVoice;
    private final NotificationService notifications;
    private final EventService events;

    public AiVoiceWorker(AiVoiceService aiVoice, NotificationService notifications, EventService events) {
        this.aiVoice = aiVoice;
        this.notifications = notifications;
        this.events = events;
    }

    @Async("voiceExecutor")
    public void acknowledgeAsync(Entry entry) {
        // V4-6.4 (2026-07-30 user audit): this was the one AI call site in the codebase with no
        // try/catch and no logging on failure — exceptions thrown from an @Async void method are
        // silently lost by the JVM (nothing to catch them; there's no caller waiting on a
        // result), so a failure here previously vanished with no event, no notification,
        // nothing. Every other AI call site (GenerationWorker, ExplainWorker, ModulePrefetchService)
        // already follows this same catch-and-log shape, per CLAUDE.md Section 3.1's "every AI
        // call site must log a brief system_events entry on fallback" rule — this was the gap.
        // Deliberately no failure notification, unlike those other workers: an acknowledgment is
        // an addendum, not content the founder is waiting on, and the frontend's own "Held."
        // fallback already covers this moment silently and gracefully — a failure here should
        // stay just as silent as a null ack already was, only now with a record of why.
        try {
            String ack = aiVoice.acknowledge(entry);
            // A null ack means no provider was configured or all failed — AiVoiceService already
            // logs that via EventService; the frontend's own "Held." fallback already covered the
            // moment, so there's nothing honest left to notify about.
            if (ack != null) {
                notifications.push(ack, Map.of("entryId", entry.getId()));
            }
        } catch (RuntimeException ex) {
            events.aiWarning("provider_error", "Capture acknowledgment failed: " + ex.getMessage(),
                    Map.of("entryId", entry.getId()));
        }
    }
}
