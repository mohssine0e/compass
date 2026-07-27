package com.compass.app.ai;

import com.compass.app.events.EventService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * V3-2.4: every AI call — success or failure — leaves one `info`-severity `system_events` record
 * naming tier, feature, provider, duration, and outcome, separate from the `aiWarning` events
 * that exist to flag degradation. Uses a mocked {@link OpenAiCompatibleChatClient} (package-
 * private, reachable from a same-package test) rather than a real provider — no network, no key.
 */
class AiCallTimingEventTest {

    private AiProperties props;
    private OpenAiCompatibleChatClient chat;
    private EventService events;
    private AiJsonGenerator generator;

    @BeforeEach
    void setUp() {
        props = new AiProperties();
        AiProperties.Provider groq = provider("groq", "llama-3.3-70b-versatile");
        props.setFast(List.of(groq));
        props.setHeavy(List.of());

        chat = mock(OpenAiCompatibleChatClient.class);
        events = mock(EventService.class);
        generator = new AiJsonGenerator(props, chat, events, new ProviderHealth(events));
    }

    private static AiProperties.Provider provider(String name, String model) {
        AiProperties.Provider p = new AiProperties.Provider();
        p.setName(name);
        p.setModel(model);
        p.setBaseUrl("https://example.invalid/v1");
        p.setApiKey("test-key");
        return p;
    }

    @Test
    @DisplayName("a successful call logs an info timing event naming tier/feature/provider/outcome")
    void successLogsTimingEvent() {
        when(chat.complete(any(), anyLong(), anyInt(), anyString(), anyString())).thenReturn("{\"ok\":true}");

        generator.generate(AiTier.FAST, "tier classification", "system", "user");

        ArgumentCaptor<Map<String, Object>> context = ArgumentCaptor.forClass(Map.class);
        verify(events).info(org.mockito.ArgumentMatchers.eq("ai_call_timing"), anyString(), context.capture());
        Map<String, Object> ctx = context.getValue();
        assertThat(ctx).containsEntry("tier", "FAST");
        assertThat(ctx).containsEntry("feature", "tier classification");
        assertThat(ctx).containsEntry("provider", "llama-3.3-70b-versatile");
        assertThat(ctx).containsEntry("outcome", "success");
        assertThat(ctx.get("durationMs")).isInstanceOf(Long.class);
    }

    @Test
    @DisplayName("a failed call still logs a timing event, with outcome=failure")
    void failureLogsTimingEventToo() {
        when(chat.complete(any(), anyLong(), anyInt(), anyString(), anyString()))
                .thenThrow(new AiCallException(AiCallException.Kind.TIMEOUT, null, "timed out", null));

        generator.generate(AiTier.FAST, "tier classification", "system", "user");

        ArgumentCaptor<Map<String, Object>> context = ArgumentCaptor.forClass(Map.class);
        verify(events).info(org.mockito.ArgumentMatchers.eq("ai_call_timing"), anyString(), context.capture());
        assertThat(context.getValue()).containsEntry("outcome", "failure");
    }

    @Test
    @DisplayName("an unusable (unparseable) reply logs success at the transport level — BAD_RESPONSE is a content problem")
    void unparseableReplyStillLogsTransportSuccess() {
        when(chat.complete(any(), anyLong(), anyInt(), anyString(), anyString())).thenReturn("not json at all");

        generator.generate(AiTier.FAST, "tier classification", "system", "user");

        ArgumentCaptor<Map<String, Object>> context = ArgumentCaptor.forClass(Map.class);
        verify(events).info(org.mockito.ArgumentMatchers.eq("ai_call_timing"), anyString(), context.capture());
        // The HTTP round trip succeeded even though the JSON didn't parse — that distinction is
        // exactly why ProviderHealth doesn't bench on BAD_RESPONSE either.
        assertThat(context.getValue()).containsEntry("outcome", "success");
    }
}
