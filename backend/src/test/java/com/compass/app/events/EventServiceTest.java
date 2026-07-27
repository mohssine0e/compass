package com.compass.app.events;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * CLAUDE.md Section 2: an event message is "one short sentence... not a stack trace" — hard
 * capped. Also confirms recording never throws, since a failing write here must never break
 * the caller's real work (capture, resurfacing, an AI fallback).
 */
class EventServiceTest {

    private EventWriter writer;
    private EventService service;

    @BeforeEach
    void setUp() {
        writer = mock(EventWriter.class);
        service = new EventService(writer, mock(SystemEventRepository.class));
    }

    @Test
    @DisplayName("a message under the cap is stored unchanged (after whitespace normalisation)")
    void shortMessageStoredAsIs() {
        service.info("test", "Fallback to Groq after Gemini timed out.", null);

        assertThat(capturedMessage()).isEqualTo("Fallback to Groq after Gemini timed out.");
    }

    @Test
    @DisplayName("a message over the cap is truncated to 280 chars with an ellipsis")
    void longMessageIsTruncated() {
        String long_ = "x".repeat(400);

        service.systemError("test", long_, null);

        String stored = capturedMessage();
        assertThat(stored).hasSizeLessThanOrEqualTo(280);
        assertThat(stored).endsWith("…");
    }

    @Test
    @DisplayName("a multi-line exception message collapses to one line before capping")
    void multiLineMessageCollapsesToOneLine() {
        String stackTraceLike = "Connection refused\n\tat com.example.Client.connect(Client.java:42)\n\tat com.example.Client.call(Client.java:10)";

        service.systemError("db_error", stackTraceLike, null);

        String stored = capturedMessage();
        assertThat(stored).doesNotContain("\n").doesNotContain("\t");
        assertThat(stored).isEqualTo("Connection refused at com.example.Client.connect(Client.java:42) at com.example.Client.call(Client.java:10)");
    }

    @Test
    @DisplayName("a null message becomes an empty string, not a null column or a thrown NPE")
    void nullMessageBecomesEmptyString() {
        service.info("test", null, null);

        assertThat(capturedMessage()).isEmpty();
    }

    @Test
    @DisplayName("recording never throws, even when the writer itself fails")
    void recordingNeverThrows() {
        writer = mock(EventWriter.class);
        org.mockito.Mockito.doThrow(new RuntimeException("db down")).when(writer).write(org.mockito.ArgumentMatchers.any());
        service = new EventService(writer, mock(SystemEventRepository.class));

        org.junit.jupiter.api.Assertions.assertDoesNotThrow(
                () -> service.systemError("db_error", "insert failed", null));
    }

    @Test
    @DisplayName("aiWarning/systemError/info/founderAction route to their documented source and severity")
    void convenienceMethodsRouteCorrectly() {
        service.aiWarning("timeout", "Groq timed out", null);
        assertThat(lastEvent().getSource()).isEqualTo(EventSource.AI_PROVIDER);
        assertThat(lastEvent().getSeverity()).isEqualTo(EventSeverity.WARNING);

        service.systemError("db_error", "insert failed", null);
        assertThat(lastEvent().getSource()).isEqualTo(EventSource.SYSTEM);
        assertThat(lastEvent().getSeverity()).isEqualTo(EventSeverity.ERROR);

        service.info("housekeeping", "pruned old events", null);
        assertThat(lastEvent().getSource()).isEqualTo(EventSource.SYSTEM);
        assertThat(lastEvent().getSeverity()).isEqualTo(EventSeverity.INFO);

        service.founderAction("mark_done", "marked step 3 done", null);
        assertThat(lastEvent().getSource()).isEqualTo(EventSource.FOUNDER);
        assertThat(lastEvent().getSeverity()).isEqualTo(EventSeverity.INFO);
    }

    private String capturedMessage() {
        return lastEvent().getMessage();
    }

    private SystemEvent lastEvent() {
        ArgumentCaptor<SystemEvent> captor = ArgumentCaptor.forClass(SystemEvent.class);
        verify(writer, org.mockito.Mockito.atLeastOnce()).write(captor.capture());
        return captor.getValue();
    }
}
