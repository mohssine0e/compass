package com.compass.app.ai;

import com.compass.app.entry.Entry;
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

    public AiVoiceWorker(AiVoiceService aiVoice, NotificationService notifications) {
        this.aiVoice = aiVoice;
        this.notifications = notifications;
    }

    @Async("voiceExecutor")
    public void acknowledgeAsync(Entry entry) {
        String ack = aiVoice.acknowledge(entry);
        // A null ack means no provider was configured or all failed — AiVoiceService already
        // logs that via EventService; the frontend's own "Held." fallback already covered the
        // moment, so there's nothing honest left to notify about.
        if (ack != null) {
            notifications.push(ack, Map.of("entryId", entry.getId()));
        }
    }
}
