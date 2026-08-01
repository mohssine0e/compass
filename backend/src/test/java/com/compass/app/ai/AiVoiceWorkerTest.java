package com.compass.app.ai;

import com.compass.app.entry.Entry;
import com.compass.app.entry.EntryType;
import com.compass.app.events.EventService;
import com.compass.app.notifications.NotificationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;

import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * V4-6.4 (2026-07-30 user audit): this was the one AI call site with no try/catch and no
 * logging on failure — an exception thrown from an {@code @Async void} method is silently lost
 * by the JVM (nothing to catch it), so a failure here previously vanished with no
 * {@code system_events} entry and no notification. Pins down that a failure is now caught and
 * logged, and — deliberately, unlike the other AI workers — that it stays just as silent to the
 * founder as a null acknowledgment already was (no failure toast; see the class's own javadoc
 * for why).
 */
class AiVoiceWorkerTest {

    private AiVoiceService aiVoice;
    private NotificationService notifications;
    private EventService events;
    private AiVoiceWorker worker;

    @BeforeEach
    void setUp() {
        aiVoice = mock(AiVoiceService.class);
        notifications = mock(NotificationService.class);
        events = mock(EventService.class);
        worker = new AiVoiceWorker(aiVoice, notifications, events);
    }

    // Same reflection-based id setter already used in RoadmapGenerationServiceTest — Entry.id
    // has no public setter (assigned on save in real use).
    private static Entry entry(long id) {
        Entry e = new Entry();
        e.setType(EntryType.IDEA);
        try {
            Field idField = Entry.class.getDeclaredField("id");
            idField.setAccessible(true);
            idField.set(e, id);
        } catch (ReflectiveOperationException ex) {
            throw new RuntimeException(ex);
        }
        return e;
    }

    @Test
    @DisplayName("a real acknowledgment is pushed as a notification")
    void realAcknowledgmentIsPushed() {
        Entry e = entry(1L);
        when(aiVoice.acknowledge(e)).thenReturn("Held.");

        worker.acknowledgeAsync(e);

        verify(notifications).push(eq("Held."), anyMap());
        verifyNoInteractions(events);
    }

    @Test
    @DisplayName("a null acknowledgment (no provider available) pushes nothing and logs nothing new — already handled by AiVoiceService")
    void nullAcknowledgmentIsQuiet() {
        Entry e = entry(1L);
        when(aiVoice.acknowledge(e)).thenReturn(null);

        worker.acknowledgeAsync(e);

        verify(notifications, never()).push(anyString(), anyMap());
        verifyNoInteractions(events);
    }

    @Test
    @DisplayName("an exception from AiVoiceService is caught, logged as a brief aiWarning event, and never pushed as a notification")
    void exceptionIsCaughtAndLogged() {
        Entry e = entry(1L);
        when(aiVoice.acknowledge(e)).thenThrow(new RuntimeException("provider exploded"));

        worker.acknowledgeAsync(e);

        verify(events).aiWarning(eq("provider_error"), org.mockito.ArgumentMatchers.contains("provider exploded"), anyMap());
        verifyNoInteractions(notifications);
    }
}
